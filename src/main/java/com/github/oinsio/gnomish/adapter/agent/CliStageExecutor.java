package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
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
import java.util.Optional;

/**
 * The real CLI {@link StageExecutor} adapter (task 6.5 of add-agent-executor):
 * one fresh {@code claude -p} subprocess per {@link #execute} call, assembling
 * every collaborator built by tasks 4–6 — {@link ExecutorPromptBuilder} for
 * the round prompt, {@link DecisionFileTransport} for the per-round
 * decision-file protocol, {@link AgentInvocationOptions#renderForExecutor}
 * for the invocation flags (including the pinpoint decision-file {@code
 * Write} allowance), {@link AgentProcessLauncher} to spawn the process, {@link
 * StreamJsonParser} to read its stream-json stdout, {@link
 * AgentRoundResultExtractor} to shape the essential result, and {@link
 * DecisionFileReader} to interpret the decision file's raw content.
 *
 * <p>Infrastructure failures — an unreadable control/criteria file ({@link
 * ControlFilePreflight.UnreadableControlFileException}), a process that
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
 * executor's rounds reaches it live, on the parse loop's own thread (FR7, NFR-O1, UX1).
 *
 * <p>Implements FR1, FR3, FR4, FR6, FR7, FR13, D1, D2, D3, D9, D10 of add-agent-executor.
 */
public final class CliStageExecutor implements StageExecutor {

    private final FactoryProperties factoryProperties;
    private final Clock clock;
    private final AgentProgressListener progressListener;
    private final ExecutorPromptBuilder promptBuilder = new ExecutorPromptBuilder();
    private final DecisionFileTransport decisionFileTransport;
    private final AgentProcessLauncher launcher;
    private final AgentRoundResultExtractor resultExtractor = new AgentRoundResultExtractor();
    private final DecisionFileReader decisionFileReader = new DecisionFileReader();

    /**
     * Equivalent to {@link #CliStageExecutor(FactoryProperties, Clock,
     * AgentProgressListener)} with a no-op listener, for callers that do not
     * need live progress (e.g. contract-suite tests focused on the port's
     * result shape, not its observability side channel).
     *
     * @param factoryProperties installation config: CLI binary path and env
     *     passthrough intent; never null
     * @param clock the read-time source for process start/exit stamping,
     *     shared with the injected {@link AgentProcessLauncher}; never null
     */
    public CliStageExecutor(FactoryProperties factoryProperties, Clock clock) {
        this(factoryProperties, clock, _ -> {});
    }

    /**
     * @param factoryProperties installation config: CLI binary path and env
     *     passthrough intent; never null
     * @param clock the read-time source for process start/exit stamping,
     *     shared with the injected {@link AgentProcessLauncher}; never null
     * @param progressListener the live-progress subscriber for this
     *     executor's rounds (design D10, task 9.4); never null — pass a
     *     {@link CompositeAgentProgressListener} to reach several
     *     subscribers, or a no-op ({@code event -> {}}) to reach none
     */
    public CliStageExecutor(FactoryProperties factoryProperties, Clock clock, AgentProgressListener progressListener) {
        this(factoryProperties, clock, progressListener, new DecisionFileTransport());
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
            DecisionFileTransport decisionFileTransport) {
        this.factoryProperties = factoryProperties;
        this.clock = clock;
        this.progressListener = progressListener;
        this.decisionFileTransport = decisionFileTransport;
        this.launcher = new AgentProcessLauncher(clock);
    }

    /**
     * Runs one fresh CLI round for {@code request} (FR1, D2): builds the
     * prompt, opens the decision-file transport, launches the process,
     * parses its stream-json output, waits for exit within {@code
     * roundTimeout}, extracts the round's essential result, and maps the
     * decision file's presence to {@link ExecutionResult.Completed} or
     * {@link ExecutionResult.DecisionNeeded}.
     *
     * <p>Implements FR1, FR3, FR4, FR13, D1, D2, D3, D9 of add-agent-executor.
     *
     * @param request the round's inputs
     * @return the round's outcome, carrying shared telemetry regardless of
     *     variant; never null
     * @throws ControlFilePreflight.UnreadableControlFileException if the
     *     control file or a judge criteria file cannot be read (FR13)
     * @throws RoundTimeoutException if {@code roundTimeout} expires (FR13)
     * @throws MissingResultEventException if no result event was emitted (FR4)
     */
    @Override
    public ExecutionResult execute(Request request) {
        String prompt = promptBuilder.build(request);
        DecisionFileTransport.Handle handle = decisionFileTransport.open();
        return runRound(request, prompt, handle);
    }

    private ExecutionResult runRound(Request request, String prompt, DecisionFileTransport.Handle handle) {
        try {
            return runRoundWithHandle(request, prompt, handle);
        } catch (RuntimeException e) {
            handle.discard();
            throw e;
        }
    }

    private ExecutionResult runRoundWithHandle(Request request, String prompt, DecisionFileTransport.Handle handle) {
        var stage = request.stage();
        var executor = stage.executor();
        var invocationFlags = AgentInvocationOptions.renderForExecutor(
                executor.model(), executor.settings(), handle.decisionFilePath());
        var workspace = (DirectoryWorkspace) request.workspace();

        LaunchedAgentProcess launched =
                launcher.launchWithFlags(workspace, prompt, factoryProperties, invocationFlags, handle.envFragment());
        if (launched == null) {
            throw new IllegalStateException("agent CLI process failed to start: " + factoryProperties.agentCliBinary());
        }

        Duration roundTimeout = RoundTimeout.resolve(executor.settings());
        var wait = launched.waitForExitOrTimeout(roundTimeout, clock);
        if (wait instanceof LaunchedAgentProcess.RoundWait.TimedOut) {
            throw new RoundTimeoutException(roundTimeout);
        }
        var wallTime = ((LaunchedAgentProcess.RoundWait.Exited) wait).wallTime();

        // The process has already exited (or been killed) by this point, so its
        // stdout pipe is fully drained and reading it here cannot block the round
        // indefinitely — reading before waitForExitOrTimeout would risk hanging on
        // a still-open pipe from a process that never exits (design D3, FR13).
        List<TimestampedEvent> events = parseStdout(launched);
        Instant roundEnd = clock.now();
        AgentRoundResult roundResult = resultExtractor.extract(events, roundEnd);
        ExecutorUsage usage = withWallTime(roundResult.usage(), wallTime);
        ToolTrace trace = trace(request, events, roundEnd);

        Optional<DecisionFileReader.Decision> decision = decisionFileReader.read(handle.readAndClose());
        return decision.map(d ->
                        (ExecutionResult) new ExecutionResult.DecisionNeeded(d.question(), d.options(), usage, trace))
                .orElseGet(() -> new ExecutionResult.Completed(usage, trace));
    }

    private List<TimestampedEvent> parseStdout(LaunchedAgentProcess launched) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(launched.process().getInputStream(), StandardCharsets.UTF_8))) {
            return new StreamJsonParser(clock, progressListener).parse(reader);
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
