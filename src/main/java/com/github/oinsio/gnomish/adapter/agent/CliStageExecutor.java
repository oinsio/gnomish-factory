package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.environment.ChildEnvAllowlist;
import com.github.oinsio.gnomish.adapter.environment.ExecCommand;
import com.github.oinsio.gnomish.adapter.environment.ExecHandle;
import com.github.oinsio.gnomish.adapter.environment.ProcessStartException;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
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
 * The real CLI {@link StageExecutor} adapter (task 6.5 of add-agent-executor):
 * one fresh {@code claude -p} subprocess per {@link #execute} call, assembling
 * every collaborator built by tasks 4–6 — {@link ExecutorPromptBuilder} for
 * the round prompt, a {@link RoundEnvironmentSource} for the round's execution
 * environment and decision transport (host default: worktree + temp-dir
 * decision file; sandboxed: the leased container environment with the
 * in-branch decision file and snapshot-closed rounds — FR4, FR21, FR23 of
 * add-sandbox-core), {@link AgentInvocationOptions#renderForExecutor} for the
 * invocation flags (including the pinpoint decision-file {@code Write}
 * allowance), {@link StreamJsonParser} to read its stream-json stdout, {@link
 * AgentRoundResultExtractor} to shape the essential result, and {@link
 * DecisionFileReader} to interpret the decision file's raw content.
 *
 * <p>Infrastructure failures — an unreadable control/criteria file ({@link
 * UnreadableLawFileException}), a process that
 * cannot even start, a {@code roundTimeout} expiry ({@link
 * RoundTimeoutException}), a missing result event ({@link
 * MissingResultEventException}) — all propagate uncaught: {@code
 * RoundExecution#execute} catches any {@link RuntimeException} this port
 * throws and shapes it into {@code RoundOutcome.CannotExecute} without
 * burning a stage attempt (NFR-R1). Both {@link ExecutionResult.Completed}
 * and {@link ExecutionResult.DecisionNeeded} carry the exact same {@code
 * usage}/{@code trace} pair — telemetry is collected from the stream
 * regardless of the round's outcome (FR3).
 *
 * <p>The {@link AgentProgressListener} supplied at construction (task 9.4, design D10) is
 * threaded straight into {@link StreamJsonParser}, so every recognized event on this
 * executor's rounds reaches it live, on the parse loop's own thread (FR7, NFR-O1, UX1);
 * the round source may add a per-round listener (the sandboxed mid-round harvest poll, FR5).
 *
 * <p>Implements FR1, FR3, FR4, FR6, FR7, FR13, D1, D2, D3, D9, D10 of add-agent-executor.
 */
public final class CliStageExecutor implements StageExecutor {

    private final FactoryProperties factoryProperties;
    private final Clock clock;
    private final AgentProgressListener progressListener;
    private final ExecutorPromptBuilder promptBuilder;
    private final RoundEnvironmentSource environmentSource;
    private final AgentRoundResultExtractor resultExtractor = new AgentRoundResultExtractor();
    private final DecisionFileReader decisionFileReader = new DecisionFileReader();

    /**
     * Equivalent to {@link #CliStageExecutor(FactoryProperties, Clock,
     * AgentProgressListener, PipelineLaw)} with a no-op listener, for callers
     * that do not need live progress (e.g. contract-suite tests focused on the
     * port's result shape, not its observability side channel).
     *
     * @param factoryProperties installation config: the CLI binary path; never null
     * @param clock the read-time source for process start/exit stamping, shared
     *     with the task environment the round runs through; never null
     * @param law the invocation's frozen pipeline law, the source of control-file
     *     and judge-criteria content (D14 of add-sandbox-core); never null
     */
    public CliStageExecutor(FactoryProperties factoryProperties, Clock clock, PipelineLaw law) {
        this(factoryProperties, clock, _ -> {}, law);
    }

    /**
     * @param factoryProperties installation config: the CLI binary path; never null
     * @param clock the read-time source for process start/exit stamping, shared
     *     with the task environment the round runs through; never null
     * @param progressListener the live-progress subscriber for this
     *     executor's rounds (design D10, task 9.4); never null — pass a
     *     {@link CompositeAgentProgressListener} to reach several
     *     subscribers, or a no-op ({@code event -> {}}) to reach none
     * @param law the invocation's frozen pipeline law (D14 of add-sandbox-core);
     *     never null
     */
    public CliStageExecutor(
            FactoryProperties factoryProperties, Clock clock, AgentProgressListener progressListener, PipelineLaw law) {
        this(factoryProperties, clock, progressListener, ChildEnvAllowlist.none(), law);
    }

    /**
     * @param factoryProperties installation config: the CLI binary path; never null
     * @param clock the read-time source for process start/exit stamping, shared
     *     with the task environment the round runs through; never null
     * @param progressListener the live-progress subscriber for this
     *     executor's rounds (design D10, task 9.4); never null
     * @param childEnv the layered child-environment allowlist every round's
     *     process environment is composed from (D6, FR9 of add-sandbox-core);
     *     never null, {@link ChildEnvAllowlist#none()} when neither passthrough
     *     nor a tracker is involved
     * @param law the invocation's frozen pipeline law (D14 of add-sandbox-core);
     *     never null
     */
    public CliStageExecutor(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            ChildEnvAllowlist childEnv,
            PipelineLaw law) {
        this(factoryProperties, clock, progressListener, new DecisionFileTransport(), childEnv, law);
    }

    /**
     * The sandboxed construction (the integration pass of add-sandbox-core): rounds run through
     * {@code environmentSource} — the leased container environment with the in-branch decision
     * file and snapshot-closed rounds — instead of the host default.
     *
     * @param factoryProperties installation config: the CLI binary path; never null
     * @param clock the read-time source for process start/exit stamping; never null
     * @param progressListener the live-progress subscriber for this executor's rounds; never null
     * @param law the invocation's frozen pipeline law (D14 of add-sandbox-core); never null
     * @param environmentSource where each round's environment and decision transport come from;
     *     never null
     */
    public CliStageExecutor(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            PipelineLaw law,
            RoundEnvironmentSource environmentSource) {
        this.factoryProperties = factoryProperties;
        this.clock = clock;
        this.progressListener = progressListener;
        this.promptBuilder = new ExecutorPromptBuilder(law);
        this.environmentSource = environmentSource;
    }

    /**
     * Testing seam (package-private): the same executor with the per-round
     * decision-file transport supplied by the caller, so a spec can assert the
     * infrastructure-failure cleanup contract — {@code runRound} must {@link
     * DecisionFileTransport.Handle#discard()} the round directory on any {@link
     * RuntimeException} (NFR-R3, D1) — without depending on the shared JVM temp
     * directory. Production always uses the no-arg transport.
     */
    CliStageExecutor(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            DecisionFileTransport decisionFileTransport,
            PipelineLaw law) {
        this(factoryProperties, clock, progressListener, decisionFileTransport, ChildEnvAllowlist.none(), law);
    }

    /** Testing seam (package-private): same as the four-argument overload, plus the allowlist. */
    CliStageExecutor(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            DecisionFileTransport decisionFileTransport,
            ChildEnvAllowlist childEnv,
            PipelineLaw law) {
        this(
                factoryProperties,
                clock,
                progressListener,
                law,
                new HostRoundEnvironmentSource(decisionFileTransport, clock, childEnv));
    }

    /**
     * Runs one fresh CLI round for {@code request} (FR1, D2): builds the
     * prompt, opens the round's environment and decision transport, launches
     * the process, parses its stream-json output, waits for exit within {@code
     * roundTimeout}, closes the round (the sandboxed snapshot commit, FR21),
     * extracts the round's essential result, and maps the decision file's
     * presence to {@link ExecutionResult.Completed} or
     * {@link ExecutionResult.DecisionNeeded}.
     *
     * <p>Implements FR1, FR3, FR4, FR13, D1, D2, D3, D9 of add-agent-executor.
     *
     * @param request the round's inputs
     * @return the round's outcome, carrying shared telemetry regardless of
     *     variant; never null
     * @throws UnreadableLawFileException if the control file or a judge criteria
     *     file was unreadable when the invocation's law was frozen (FR13, D14)
     * @throws RoundTimeoutException if {@code roundTimeout} expires (FR13)
     * @throws MissingResultEventException if no result event was emitted (FR4)
     */
    @Override
    public ExecutionResult execute(Request request) {
        String prompt = promptBuilder.build(request);
        RoundEnvironmentSource.Round round = environmentSource.openRound(request);
        return runRound(request, prompt, round);
    }

    private ExecutionResult runRound(Request request, String prompt, RoundEnvironmentSource.Round round) {
        try {
            return runRoundInEnvironment(request, prompt, round);
        } catch (RuntimeException e) {
            round.discard();
            throw e;
        }
    }

    private ExecutionResult runRoundInEnvironment(Request request, String prompt, RoundEnvironmentSource.Round round) {
        var stage = request.stage();
        var executor = stage.executor();
        var invocationFlags = AgentInvocationOptions.renderForExecutor(
                executor.model(), executor.settings(), round.decisionFilePath());
        List<String> command = AgentCommandLine.fromRenderedFlags(factoryProperties.agentCliBinary(), invocationFlags);

        // Factory-set protocol layer (D6, FR9): the AI seam variables plus this round's
        // decision-file path — the only variables beyond base and passthrough a round sees.
        Map<String, String> env = new java.util.LinkedHashMap<>(AgentAiSeam.fromFactoryEnvironment());
        env.putAll(round.decisionEnvFragment());
        ExecHandle launched = launch(round, command, prompt, env);

        Duration roundTimeout = RoundTimeout.resolve(executor.settings());
        var wait = launched.waitForExitOrTimeout(roundTimeout, clock);
        if (wait instanceof ExecHandle.Wait.TimedOut) {
            throw new RoundTimeoutException(roundTimeout);
        }
        var wallTime = ((ExecHandle.Wait.Exited) wait).wallTime();

        // The process has already exited (or been killed) by this point, so its
        // stdout pipe is fully drained and reading it here cannot block the round
        // indefinitely — reading before waitForExitOrTimeout would risk hanging on
        // a still-open pipe from a process that never exits (design D3, FR13).
        List<TimestampedEvent> events = parseStdout(launched, round.roundListener());
        Instant roundEnd = clock.now();
        AgentRoundResult roundResult = resultExtractor.extract(events, roundEnd);
        ExecutorUsage usage = withWallTime(roundResult.usage(), wallTime);
        ToolTrace trace = trace(request, events, roundEnd);

        // The sandboxed snapshot commit + harvest close the gnome half of the round here (FR21,
        // D15) — before the decision read, so a pending decision request rides the snapshot (D17).
        round.closeRound();

        Optional<DecisionFileReader.Decision> decision = decisionFileReader.read(round.readDecision());
        return decision.map(d ->
                        (ExecutionResult) new ExecutionResult.DecisionNeeded(d.question(), d.options(), usage, trace))
                .orElseGet(() -> new ExecutionResult.Completed(usage, trace));
    }

    private ExecHandle launch(
            RoundEnvironmentSource.Round round, List<String> command, String prompt, Map<String, String> env) {
        try {
            return round.environment().exec(new ExecCommand(command, env, prompt, false));
        } catch (ProcessStartException e) {
            throw new IllegalStateException(
                    "agent CLI process failed to start: " + factoryProperties.agentCliBinary(), e);
        }
    }

    private List<TimestampedEvent> parseStdout(ExecHandle launched, AgentProgressListener roundListener) {
        var listener = new CompositeAgentProgressListener(List.of(progressListener, roundListener));
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(launched.output(), StandardCharsets.UTF_8))) {
            return new StreamJsonParser(clock, listener).parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read agent process stdout", e);
        }
    }

    private static ExecutorUsage withWallTime(ExecutorUsage usage, Duration wallTime) {
        return new ExecutorUsage(wallTime, usage.tools(), usage.tokensByModel());
    }

    private static ToolTrace trace(Request request, List<TimestampedEvent> events, Instant roundEnd) {
        AttemptKey key =
                new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt());
        return new ToolTrace(key, new ToolTraceBuilder().buildTrace(events, roundEnd));
    }
}
