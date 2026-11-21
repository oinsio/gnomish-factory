package com.github.oinsio.gnomish.adapter.agent.claude;

import com.github.oinsio.gnomish.adapter.agent.runner.AgentDialect;
import com.github.oinsio.gnomish.adapter.agent.runner.DialectAnswer;
import com.github.oinsio.gnomish.adapter.agent.runner.ParsedEvents;
import com.github.oinsio.gnomish.adapter.agent.runner.ParsedLine;
import com.github.oinsio.gnomish.adapter.agent.runner.RenderedArgv;
import com.github.oinsio.gnomish.adapter.agent.runner.RoundStatus;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorSettings;
import com.github.oinsio.gnomish.executorcontract.Capability;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The Claude Code dialect of the agent round runner (FR2, FR3, design D1 of
 * extract-agent-round-runner): {@code claude -p} with the prompt on stdin and {@code --output-format
 * stream-json --verbose}, parsed line by line through the stream-json reader this package owns, with
 * per-model usage and the top-level tool trace derived exactly as the round result always derived
 * them. A line, its progress and the round's telemetry each have one owner here — {@link
 * StreamJsonParser#recognize}, {@link AgentProgressEmitter#progressOf} and {@link
 * AgentRoundResultExtractor#usage} — shared with the stream-json path the old round classes still run
 * until they are deleted.
 *
 * <p>Status: a result event whose subtype is the CLI's turn or budget stop is a limit stop naming
 * the stop; {@code error_max_structured_output_retries} is completed, its answer malformed; any other
 * {@code error_*} subtype, and a stream with no result event, is a broken round. The exit code is
 * not consulted beyond that: the result event is what the CLI's own contract makes essential.
 *
 * <p>The argv is {@link AgentCommandLine}'s: the request's policy compiled by {@link
 * ClaudePolicyCompiler} and its turn and budget bounds rendered as the CLI's hints (task 2.2).
 *
 * <p>Describe (task 2.3, FR7, NFR-S1): roles {@code transform}, {@code verdict}, {@code decision};
 * capabilities {@code streams_progress} and {@code cancel}, never {@code resumable}; enforcement level {@code
 * TOOL_GATE}; the credentials, configuration-directory variable and repo-policy-disabling flags of
 * {@link ClaudeEnvironment}; the one egress host, that of the configured base URL; and the settings
 * schema of {@link ClaudeSettings}. Answers (task 2.4, FR3, FR4): a {@code verdict} or {@code
 * decision} argv ends with {@code --json-schema} and {@link ClaudeAnswerSchema}'s reply schema, and
 * {@link ClaudeAnswer} judges the reply. No session (task 2.5, FR9, NFR-R2, design D4 as amended
 * 2026-11-21): the CLI's session lives on the worker, so no argv carries a session flag and the stdin
 * is always the role's input ({@link ClaudePrompt}).
 *
 * <p>Implements FR2, FR3, FR4, FR7, FR9, NFR-R2, NFR-S1 of extract-agent-round-runner.
 */
public final class ClaudeDialect implements AgentDialect<AgentEvent> {

    /** The result subtype of a round the CLI stopped on its turn limit. */
    static final String MAX_TURNS_SUBTYPE = "error_max_turns";

    /** The result subtype of a round the CLI stopped on its budget limit. */
    static final String MAX_BUDGET_SUBTYPE = "error_max_budget_usd";

    private static final String ERROR_SUBTYPE_PREFIX = "error_";

    private static final String JSON_SCHEMA_FLAG = "--json-schema";

    private final String binary;
    private final UntrustedText version;
    private final String egressHost;
    private final TokenUsageMapper tokenUsageMapper = new TokenUsageMapper();
    private final AgentRoundResultExtractor extractor = new AgentRoundResultExtractor();

    /**
     * @param binary the CLI binary name or path; never null
     * @param version the CLI's version as it reported it; never blank
     * @param egressHost the host the CLI reaches; never blank
     */
    ClaudeDialect(String binary, UntrustedText version, String egressHost) {
        this.binary = binary;
        this.version = version;
        this.egressHost = egressHost;
    }

    /**
     * The dialect of one run: its egress host is the host of the base URL the run's allowlist
     * resolves — the secrets port, in production — or the provider's own host when none is set.
     *
     * @param binary the CLI binary name or path; never null
     * @param version the CLI's version as it reported it; never blank
     * @param childEnv the run's child-environment allowlist; never null
     * @return the dialect; never null
     * @throws IllegalArgumentException if the configured base URL names no host
     */
    public static ClaudeDialect of(String binary, UntrustedText version, ChildEnvAllowlist childEnv) {
        return new ClaudeDialect(binary, version, ClaudeEnvironment.egressHost(childEnv));
    }

    @Override
    public Describe describe() {
        return new Describe(
                Describe.SPOKEN_PROTOCOL_VERSION,
                version,
                List.of(Role.TRANSFORM, Role.VERDICT, Role.DECISION),
                List.of(Capability.STREAMS_PROGRESS, Capability.CANCEL),
                ClaudeSettings.schema(),
                ClaudeEnvironment.CREDENTIALS,
                List.of(egressHost),
                ClaudeEnvironment.CONFIG_DIR_VARIABLE,
                ClaudeEnvironment.POLICY_DISABLING_FLAGS,
                EnforcementLevel.TOOL_GATE,
                true,
                false);
    }

    @Override
    public RenderedArgv render(ExecutorRequest request) {
        Map<String, Object> settings = ExecutorContractJson.settingsValues(request.settings());
        String model = settings.get(ExecutorSettings.MODEL_KEY) instanceof String named ? named : "";
        List<String> argv =
                new ArrayList<>(AgentCommandLine.of(binary, model, settings, request.policy(), request.bounds()));
        ClaudeAnswerSchema.of(request).ifPresent(schema -> {
            argv.add(JSON_SCHEMA_FLAG);
            argv.add(schema.toString());
        });
        return new RenderedArgv(argv, ClaudePrompt.of(request));
    }

    @Override
    public ParsedLine<AgentEvent> parse(String line, ParsedEvents<AgentEvent> earlier) {
        Optional<AgentEvent> recognized = StreamJsonParser.recognize(line);
        if (recognized.isEmpty()) {
            return ParsedLine.nothing();
        }
        AgentEvent event = recognized.get();
        AgentEvent.@Nullable InitEvent init =
                event instanceof AgentEvent.InitEvent current ? current : earlier.last(AgentEvent.InitEvent.class);
        return new ParsedLine<>(List.of(event), AgentProgressEmitter.progressOf(event, init, tokenUsageMapper));
    }

    @Override
    public Usage usage(ParsedEvents<AgentEvent> events, Instant roundEnd) {
        return ContractUsage.of(extractor.usage(timestamped(events), roundEnd));
    }

    @Override
    public DialectAnswer answer(ExecutorRequest request, ParsedEvents<AgentEvent> events, Path answerFile) {
        return ClaudeAnswer.of(request, events, answerFile);
    }

    @Override
    public RoundStatus status(int exitCode, ParsedEvents<AgentEvent> events) {
        AgentEvent.ResultEvent result = events.last(AgentEvent.ResultEvent.class);
        if (result == null) {
            return new RoundStatus.Broken(UntrustedText.factory("stream-json carried no result event"));
        }
        String subtype = result.subtype();
        if (subtype == null
                || !subtype.startsWith(ERROR_SUBTYPE_PREFIX)
                || subtype.equals(ClaudeAnswer.RETRIES_EXHAUSTED_SUBTYPE)) {
            return new RoundStatus.Completed();
        }
        return switch (subtype) {
            case MAX_TURNS_SUBTYPE -> new RoundStatus.LimitReached("the CLI's turn limit (" + MAX_TURNS_SUBTYPE + ")");
            case MAX_BUDGET_SUBTYPE ->
                new RoundStatus.LimitReached("the CLI's budget limit (" + MAX_BUDGET_SUBTYPE + ")");
            default -> new RoundStatus.Broken(UntrustedText.agent("the CLI ended the round as " + subtype));
        };
    }

    private static List<TimestampedEvent> timestamped(ParsedEvents<AgentEvent> events) {
        return events.stamped().stream()
                .map(stamped -> new TimestampedEvent(stamped.event(), stamped.at()))
                .toList();
    }
}
