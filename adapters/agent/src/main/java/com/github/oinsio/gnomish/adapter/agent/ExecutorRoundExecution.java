package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Runs one CLI executor round to an {@link ExecutionResult} (FR1, FR3, FR4, FR13, D1, D2, D3, D9
 * of add-agent-executor): launches the process, drains and parses its stream-json stdout
 * concurrently through a {@link StreamDrain}, waits for exit within {@code roundTimeout}, closes
 * the round, and reads the decision file. Extracted from {@link CliStageExecutor} for file size.
 *
 * <p>Kept in sync with {@link JudgeRoundExecution}: both launch one agent round from the same
 * {@link AgentRoundEquipment} — the CLI binary through the environment port with the factory-set
 * AI seam variables and the CLI's stderr sent to a {@link RoundScratchDirectory} that leaves with
 * the round, a {@link StreamDrain} started before the wait, the wait bounded by {@code
 * roundTimeout} with timeout and interrupt classified before the drain's events are consulted,
 * then the handle's {@link ExecHandle#postExitBound()} for the tail, never a bound of its own —
 * and each maps every failure of that launch sequence through one site that carries the stderr
 * tail of the round (here: {@link ExecutorFailure}; there: {@link JudgeCannotVerify}), leaving it
 * one DEBUG line on success. Both
 * probe the environment's egress guard before the launch and again once the process has exited,
 * before anything is graded (D14 of tighten-spec-contract).
 *
 * <p>Implements FR1, FR2, FR3, FR6, NFR-R1, NFR-R2 of fix-round-stdout-drain; FR1 of
 * fix-denial-attribution-durability; FR6 of add-parameter-count-gate (design D6, Sync surfaces);
 * NFR-R1 of add-sandbox-core as probed per round by D14 of tighten-spec-contract; FR19 of
 * fix-terminal-receipt-convergence (design D17 as amended 2026-10-17: the post-exit wait is the
 * handle's bound); FR20 (design D18: the CLI's stderr goes to the round scratch directory; as amended 2026-10-17, its tail rides the
 * failure).
 */
final class ExecutorRoundExecution {

    /** The tail of a round whose scratch directory could not be made: no process ran. */
    private static final UntrustedText NO_STDERR = UntrustedText.agent("");

    private ExecutorRoundExecution() {}

    /**
     * Runs the round already opened as {@code round}. Throws on infrastructure failure (unlike
     * the judge's round execution) — {@code RoundExecution#execute} maps any {@link
     * RuntimeException} to {@code RoundOutcome.CannotExecute} without burning a stage attempt
     * (NFR-R1); this method itself never discards the round on failure — the caller does, so the
     * discard happens exactly once regardless of where in this method the failure occurred.
     *
     * <p>The throw is an {@link ExecutorFailure} wrapping the original exception together with
     * the denials drained from the dead round ({@link RoundDenialRead#onFailure}), so a gnome
     * that hung while attempting a blocked egress reports that denial on the escalation instead
     * of only in the factory log (FR1 of fix-denial-attribution-durability, design D1). Every
     * failure of the round is wrapped, the launch included — the three ExecutorFailure names.
     */
    static ExecutionResult run(
            AgentRoundEquipment equipment,
            DecisionFileReader decisionFileReader,
            StageExecutor.Request request,
            String prompt,
            RoundEnvironmentSource.Round round) {
        FactoryProperties factoryProperties = equipment.factoryProperties();
        InstantSource clock = equipment.clock();
        var stage = request.stage();
        var executor = stage.executor();
        var invocationFlags = AgentInvocationOptions.renderForExecutor(
                executor.model(), executor.settings(), round.decisionFilePath());
        List<String> command = AgentCommandLine.fromRenderedFlags(
                AgentRole.EXECUTOR, factoryProperties.agentCliBinary(), invocationFlags);

        // Factory-set protocol layer (D6, FR9): the AI seam variables plus this round's
        // decision-file path — the only variables beyond base and passthrough a round sees.
        Map<String, String> env = new java.util.LinkedHashMap<>(AgentAiSeam.variables(equipment.childEnv()));
        env.putAll(round.decisionEnvFragment());
        // The launch is inside the wrapped region, not before it: a process that would not
        // start is a round that died before its close exactly like a roundTimeout kill, and
        // ExecutorFailure promises the denials of all three. A box whose materialization was
        // itself partly denied has them recorded before the first process ever runs. The scratch
        // directory (D18 of fix-terminal-receipt-convergence) takes the CLI's stderr; the catch
        // reads its tail into the failure before the finally removes it, and a round that
        // succeeded leaves the tail as one DEBUG line (D18 as amended 2026-10-17).
        @Nullable RoundScratchDirectory scratch = null;
        try {
            scratch = RoundScratchDirectory.open(equipment.roundScratchParent());
            // D14 of tighten-spec-contract: an in-box retry reuses the box, and with it a guard
            // that may have died after the previous round closed. A dead guard fails this round
            // before it starts, as infrastructure; a guard-less (host) environment answers at once.
            round.environment().probeGuard();
            ExecHandle launched =
                    launch(factoryProperties, round, new ExecCommand(command, env, prompt, scratch.stderr()));
            // The stdout drain starts here, before the wait, and runs concurrently with the
            // process (FR1, D1 of fix-round-stdout-drain): deferring the read until after exit
            // let a stream larger than the ~64 KB OS pipe buffer either block the child on a
            // full pipe until the roundTimeout kill or lose its tail — and the tail is where
            // the essential result event lives. try-with-resources is what guarantees no drain
            // thread outlives the round on any exit path (NFR-R1): its close stops the drain and
            // closes no pipe, which is the JDK's to close at the exit (D5 of make-pipe-reads-non-parking).
            try (StreamDrain drain =
                    StreamDrain.start(launched.output(), clock, listenerFor(equipment.progressListener(), round))) {
                Duration roundTimeout = RoundTimeout.resolve(executor.settings());
                var wait = launched.waitForExitOrTimeout(roundTimeout, clock);
                // Both early endings are classified before the drain's events are consulted
                // (FR3): the kill ended the stream mid-line, and that secondary symptom must not
                // mask what actually ended the round. An interrupt is its own failure, never the
                // budget's (FR6, FR11 of bound-subprocess-commands).
                var wallTime =
                        switch (wait) {
                            case ExecHandle.Wait.Exited exited -> exited.wallTime();
                            case ExecHandle.Wait.TimedOut ignored -> throw new RoundTimeoutException(roundTimeout);
                            case ExecHandle.Wait.Interrupted ignored -> throw new RoundInterruptedException();
                        };
                // D14 of tighten-spec-contract: the close of the round probes the guard before
                // anything is graded or committed — a guard that died while the round ran broke
                // its cage, so the round dies here as infrastructure, nothing graded; the denial
                // read below cannot be the detector: it is fail-open by design.
                round.environment().probeGuard();

                List<TimestampedEvent> events = drain.await(launched.postExitBound());
                Instant roundEnd = clock.instant();
                AgentRoundResult roundResult = equipment.resultExtractor().extract(events, roundEnd, drain.bytesRead());
                ExecutorUsage usage = withWallTime(roundResult.usage(), wallTime);
                ToolTrace trace = trace(request, events, roundEnd);

                // The sandboxed snapshot commit + harvest close the gnome half of the round here
                // (FR21, D15) — before the decision read, so a pending decision request rides the
                // snapshot (D17).
                round.closeRound();

                // FR3, D1 of fix-denial-report-attachment: the environment's denials are
                // round-close data, read once the gnome half is over and carried out on the
                // ExecutionResult exactly like usage and trace.
                List<Denial> denials = RoundDenialRead.onFinish(round);

                Optional<DecisionFileReader.Decision> decision = decisionFileReader.read(round.readDecision());
                scratch.logTailOnSuccess();
                return decision.map(d -> (ExecutionResult)
                                new ExecutionResult.DecisionNeeded(d.question(), d.options(), usage, trace, denials))
                        .orElseGet(() -> new ExecutionResult.Completed(usage, trace, denials));
            }
        } catch (RuntimeException e) {
            // FR1 of fix-denial-attribution-durability: the drained denials leave the round
            // with the failure instead of only reaching the log. The original exception stays
            // the cause, so the engine's escalation text is unchanged but for the stderr tail
            // (FR20 of fix-terminal-receipt-convergence); the round is still discarded exactly
            // once by the caller. No directory means no process ran, so no tail.
            UntrustedText tail = scratch == null ? NO_STDERR : scratch.tailForFailure();
            throw new ExecutorFailure(e, RoundDenialRead.onFailure(round), tail);
        } finally {
            if (scratch != null) {
                scratch.close();
            }
        }
    }

    private static ExecHandle launch(
            FactoryProperties factoryProperties, RoundEnvironmentSource.Round round, ExecCommand command) {
        try {
            return round.environment().exec(command);
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
