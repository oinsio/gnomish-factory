package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The real CLI {@link JudgeVoter} adapter (task 7.5 of add-agent-executor):
 * one fresh {@code claude -p} subprocess per {@link #vote} call, assembling
 * {@link JudgeCriteriaPreflight} for the criteria-readability precheck,
 * {@link JudgePromptBuilder} for the round prompt, {@link
 * AgentInvocationOptions#renderForJudge} for the hard-wired read-only
 * invocation flags, {@link AgentProcessLauncher} to spawn the process, {@link
 * StreamJsonParser} to read its stream-json stdout, {@link
 * AgentRoundResultExtractor} to shape the essential result, and {@link
 * JudgeVerdictExtractor} to interpret the round's final message.
 *
 * <p>Unlike {@link CliStageExecutor}, this port never throws (design D5,
 * NFR-R1): the judge's degradation direction is inverted from the
 * executor's, since the judge IS the QC net — an unreadable criteria file, a
 * process that cannot start, a {@code roundTimeout} expiry, or a missing
 * result event are all mapped to a normal {@link JudgeVoter.Vote} carrying
 * {@link Verdict.CannotVerify}, never an uncaught exception. {@link
 * JudgeCriteriaPreflight#checkReadable} runs first, before any prompt is
 * built or process spawned (FR13): "never a criteria-less vote".
 *
 * <p>The {@link AgentProgressListener} supplied at construction (task 9.4, design D10) is
 * threaded straight into {@link StreamJsonParser}: judge rounds feed the same live-progress
 * stream as executor rounds, indistinguishable in shape — the run assembly is expected to wire
 * the shared {@link LoggingAgentProgressListener} renderer alone here, never the executor-only
 * status enricher (FR7, D10).
 *
 * <p>Implements FR7, FR8, FR9, FR12, FR13, D5, D7, D10 of add-agent-executor; cross-references
 * NFR-R1 of add-stage-engine.
 */
public final class CliJudgeVoter implements JudgeVoter {

    private final FactoryProperties factoryProperties;
    private final Clock clock;
    private final AgentProgressListener progressListener;
    private final JudgePromptBuilder promptBuilder = new JudgePromptBuilder();
    private final AgentProcessLauncher launcher;
    private final AgentRoundResultExtractor resultExtractor = new AgentRoundResultExtractor();
    private final JudgeVerdictExtractor verdictExtractor = new JudgeVerdictExtractor();

    /**
     * Equivalent to {@link #CliJudgeVoter(FactoryProperties, Clock,
     * AgentProgressListener)} with a no-op listener, for callers that do not
     * need live progress (e.g. contract-suite tests focused on the port's
     * verdict shape, not its observability side channel).
     *
     * @param factoryProperties installation config: CLI binary path and env
     *     passthrough intent; never null
     * @param clock the read-time source for process start/exit stamping,
     *     shared with the injected {@link AgentProcessLauncher}; never null
     */
    public CliJudgeVoter(FactoryProperties factoryProperties, Clock clock) {
        this(factoryProperties, clock, event -> {});
    }

    /**
     * @param factoryProperties installation config: CLI binary path and env
     *     passthrough intent; never null
     * @param clock the read-time source for process start/exit stamping,
     *     shared with the injected {@link AgentProcessLauncher}; never null
     * @param progressListener the live-progress subscriber for this judge's
     *     rounds (design D10, task 9.4); judge rounds feed the same {@link
     *     LoggingAgentProgressListener} renderer as executor rounds, never
     *     the status enricher (a vote runs under the verifying activity);
     *     never null — pass a no-op ({@code event -> {}}) to reach none
     */
    public CliJudgeVoter(FactoryProperties factoryProperties, Clock clock, AgentProgressListener progressListener) {
        this.factoryProperties = factoryProperties;
        this.clock = clock;
        this.progressListener = progressListener;
        this.launcher = new AgentProcessLauncher(clock);
    }

    /**
     * Casts one fresh CLI judge round for {@code check} (FR8, D5, D7):
     * checks the criteria file is readable first, returning immediately with
     * no process spawned if not (FR13); otherwise builds the prompt, renders
     * the hard-wired read-only invocation flags, launches the process,
     * parses its stream-json output, waits for exit within {@code
     * roundTimeout}, extracts the round's essential result, and grades the
     * final message into a {@link Verdict}.
     *
     * <p>Implements FR8, FR9, FR12, FR13, D5, D7 of add-agent-executor.
     *
     * @param check the judge check whose criteria and model settings drive the vote
     * @param context the task's identity and human decisions
     * @param workspace the working copy being graded; must be a {@link DirectoryWorkspace}
     * @return the vote's verdict and per-model token usage; never null, never throws
     */
    @Override
    public Vote vote(VerifyCheck.Judge check, TaskContext context, Workspace workspace) {
        var root = ((DirectoryWorkspace) workspace).root();

        Optional<Verdict.CannotVerify> preflight = JudgeCriteriaPreflight.checkReadable(root, check);
        if (preflight.isPresent()) {
            return new Vote(preflight.get(), Map.of());
        }

        // JudgePromptBuilder re-reads the same criteria file JudgeCriteriaPreflight
        // just confirmed readable via the identical ControlFilePreflight#read call;
        // a second failure here would require the file to change between the two
        // reads (a genuine TOCTOU race), which is not worth a defensive try/catch —
        // any such race would be an infrastructure oddity indistinguishable from
        // the process simply failing to start below.
        String prompt = promptBuilder.build(check, context, workspace);
        return runRound(check, root, prompt);
    }

    private Vote runRound(VerifyCheck.Judge check, java.nio.file.Path root, String prompt) {
        var invocationFlags = AgentInvocationOptions.renderForJudge(check.model(), check.settings());
        var workspace = new DirectoryWorkspace(root);

        LaunchedAgentProcess launched =
                launcher.launchWithFlags(workspace, prompt, factoryProperties, invocationFlags, Map.of());
        if (launched == null) {
            return cannotVerify("agent CLI process failed to start", factoryProperties.agentCliBinary());
        }

        Duration roundTimeout = RoundTimeout.resolve(check.settings());
        var wait = launched.waitForExitOrTimeout(roundTimeout, clock);
        if (wait instanceof LaunchedAgentProcess.RoundWait.TimedOut) {
            return cannotVerify("agent round exceeded roundTimeout and was killed", "roundTimeout: " + roundTimeout);
        }

        // The process has already exited (or been killed) by this point, so its
        // stdout pipe is fully drained and reading it here cannot block indefinitely
        // (design D3, FR13) — same rationale as CliStageExecutor.
        List<TimestampedEvent> events = parseStdout(launched);
        Instant roundEnd = clock.now();
        try {
            AgentRoundResult roundResult = resultExtractor.extract(events, roundEnd);
            Verdict verdict = verdictExtractor.extract(roundResult.result());
            return new Vote(verdict, roundResult.usage().tokensByModel());
        } catch (MissingResultEventException e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return cannotVerify("stream-json carried no result event for round", message);
        }
    }

    private List<TimestampedEvent> parseStdout(LaunchedAgentProcess launched) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(launched.process().getInputStream(), StandardCharsets.UTF_8))) {
            return new StreamJsonParser(clock, progressListener).parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read agent process stdout", e);
        }
    }

    private static Vote cannotVerify(String reason, String details) {
        return new Vote(new Verdict.CannotVerify(reason, details), Map.of());
    }
}
