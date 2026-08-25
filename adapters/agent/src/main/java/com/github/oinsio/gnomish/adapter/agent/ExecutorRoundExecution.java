package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one CLI executor round to an {@link ExecutionResult} (FR1, FR3, FR4, FR13, D1, D2, D3, D9
 * of add-agent-executor): launches the process, drains and parses its stream-json stdout
 * concurrently through a {@link StreamDrain}, waits for exit within {@code roundTimeout}, closes
 * the round, and reads the decision file. Extracted from {@link CliStageExecutor} for file size.
 *
 * <p>Implements FR1, FR2, FR3, FR6, NFR-R1, NFR-R2 of fix-round-stdout-drain.
 */
final class ExecutorRoundExecution {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRoundExecution.class);

    private ExecutorRoundExecution() {}

    /**
     * Runs the round already opened as {@code round}. Throws on infrastructure failure (unlike
     * the judge's round execution) — {@code RoundExecution#execute} maps any {@link
     * RuntimeException} to {@code RoundOutcome.CannotExecute} without burning a stage attempt
     * (NFR-R1); this method itself never discards the round on failure — the caller does, so the
     * discard happens exactly once regardless of where in this method the failure occurred.
     */
    static ExecutionResult run(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            AgentRoundResultExtractor resultExtractor,
            DecisionFileReader decisionFileReader,
            StageExecutor.Request request,
            String prompt,
            RoundEnvironmentSource.Round round) {
        var stage = request.stage();
        var executor = stage.executor();
        var invocationFlags = AgentInvocationOptions.renderForExecutor(
                executor.model(), executor.settings(), round.decisionFilePath());
        List<String> command = AgentCommandLine.fromRenderedFlags(factoryProperties.agentCliBinary(), invocationFlags);

        // Factory-set protocol layer (D6, FR9): the AI seam variables plus this round's
        // decision-file path — the only variables beyond base and passthrough a round sees.
        Map<String, String> env = new java.util.LinkedHashMap<>(AgentAiSeam.fromFactoryEnvironment());
        env.putAll(round.decisionEnvFragment());
        ExecHandle launched = launch(factoryProperties, round, command, prompt, env);
        // The stdout drain starts here, before the wait, and runs concurrently with the
        // process (FR1, D1 of fix-round-stdout-drain): deferring the read until after exit
        // let a stream larger than the ~64 KB OS pipe buffer either block the child on a
        // full pipe until the roundTimeout kill or lose its tail — and the tail is where
        // the essential result event lives. try-with-resources is what guarantees no drain
        // thread and no open stream outlives the round on any exit path (NFR-R1).
        try (StreamDrain drain = StreamDrain.start(launched.output(), clock, listenerFor(progressListener, round))) {
            Duration roundTimeout = RoundTimeout.resolve(executor.settings());
            var wait = launched.waitForExitOrTimeout(roundTimeout, clock);
            // Both early endings are classified before the drain's events are consulted (FR3):
            // the kill closed the pipe mid-read, and that secondary symptom must not mask what
            // actually ended the round. An interrupt is its own failure, never the budget's
            // (FR6, FR11 of bound-subprocess-commands).
            var wallTime =
                    switch (wait) {
                        case ExecHandle.Wait.Exited exited -> exited.wallTime();
                        case ExecHandle.Wait.TimedOut ignored -> throw new RoundTimeoutException(roundTimeout);
                        case ExecHandle.Wait.Interrupted ignored -> throw new RoundInterruptedException();
                    };

            List<TimestampedEvent> events = drain.await(factoryProperties.agentCliTailDrainGrace());
            Instant roundEnd = clock.now();
            AgentRoundResult roundResult = resultExtractor.extract(events, roundEnd, drain.bytesRead());
            ExecutorUsage usage = withWallTime(roundResult.usage(), wallTime);
            ToolTrace trace = trace(request, events, roundEnd);

            // The sandboxed snapshot commit + harvest close the gnome half of the round here
            // (FR21, D15) — before the decision read, so a pending decision request rides the
            // snapshot (D17).
            round.closeRound();

            // FR3, D1 of fix-denial-report-attachment: the environment's denials are round-close
            // data, read once the gnome half is over and carried out on the ExecutionResult
            // exactly like usage and trace.
            List<Finding> denials = denialsOf(round);

            Optional<DecisionFileReader.Decision> decision = decisionFileReader.read(round.readDecision());
            return decision.map(d -> (ExecutionResult)
                            new ExecutionResult.DecisionNeeded(d.question(), d.options(), usage, trace, denials))
                    .orElseGet(() -> new ExecutionResult.Completed(usage, trace, denials));
        } catch (RuntimeException e) {
            drainDenials(round);
            throw e;
        }
    }

    /**
     * The denials of a round that reached its close, or none when the environment cannot answer
     * (NFR-R1 of fix-denial-report-attachment). The port promises a degraded empty answer for an
     * unreadable log and a guard-less environment, but the round is already finished by this
     * point — its usage, trace and committed snapshot all exist — so a throwing observability
     * read must not be what discards it. Same best-effort stance as {@link #drainDenials}, on the
     * side where there IS an attempt record to carry the result.
     */
    private static List<Finding> denialsOf(RoundEnvironmentSource.Round round) {
        try {
            return round.environment().denialFindings();
        } catch (RuntimeException e) {
            log.warn("could not read the egress denials of a finished round; reporting none", e);
            return List.of();
        }
    }

    /**
     * Reads and logs the denials of a round that died before its close — a {@code roundTimeout}
     * kill, a missing result event (D1 of fix-denial-report-attachment). Such a round produces no
     * {@code AttemptRecord} (the engine shapes the throw into {@code RoundOutcome.CannotExecute}),
     * so there is nothing to attach them to; draining them anyway is what keeps them from becoming
     * the NEXT round's report, since the guard's per-round delta cursor advances only on a read and
     * an in-process resume reuses the same environment. Best-effort squared: the read is already
     * best-effort (NFR-R1) and a throw out of it here would mask the infrastructure failure that
     * brought the round down, so it is caught and logged.
     */
    private static void drainDenials(RoundEnvironmentSource.Round round) {
        try {
            List<Finding> denials = round.environment().denialFindings();
            log.warn(
                    "round failed before close; {} egress denial(s) drained, attached to no attempt: {}",
                    denials.size(),
                    denials);
        } catch (RuntimeException e) {
            log.warn("could not read the egress denials of a failed round", e);
        }
    }

    private static ExecHandle launch(
            FactoryProperties factoryProperties,
            RoundEnvironmentSource.Round round,
            List<String> command,
            String prompt,
            Map<String, String> env) {
        try {
            return round.environment().exec(new ExecCommand(command, env, prompt, false));
        } catch (ProcessStartException e) {
            throw new IllegalStateException(
                    "agent CLI process failed to start: " + factoryProperties.agentCliBinary(), e);
        }
    }

    /**
     * The round's live-progress fan-out — the executor's own subscriber plus whatever
     * per-round listener the environment added (the sandboxed mid-round harvest poll).
     * Since fix-round-stdout-drain it is invoked from the drain thread, per line, while
     * the process still runs (FR4, D4); the composite's per-listener swallowing is
     * unchanged.
     */
    private static AgentProgressListener listenerFor(
            AgentProgressListener progressListener, RoundEnvironmentSource.Round round) {
        return new CompositeAgentProgressListener(List.of(progressListener, round.roundListener()));
    }

    private static ExecutorUsage withWallTime(ExecutorUsage usage, Duration wallTime) {
        return new ExecutorUsage(wallTime, usage.tools(), usage.tokensByModel());
    }

    private static ToolTrace trace(StageExecutor.Request request, List<TimestampedEvent> events, Instant roundEnd) {
        AttemptKey key =
                new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt());
        return new ToolTrace(key, new ToolTraceBuilder().buildTrace(events, roundEnd));
    }
}
