package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TaskSummaryAssembler;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.logtext.ShutdownPhase;
import com.github.oinsio.gnomish.status.AnchorLog;
import com.github.oinsio.gnomish.status.TaskSummary;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;

/**
 * What a finished slot tells the operator, owned here so {@link TakeSlotRunner} holds the take
 * cycle and this holds the lines it ends on ({@code MidRoundPollLog}'s split, one layer up).
 *
 * <p>Two lines per slot, in this order: the per-outcome <em>detail</em> at DEBUG — the free text a
 * terminal variant carries that the summary's fixed vocabulary has no room for — and then the
 * canonical {@link AnchorLog#taskSummary} last, so a {@code grep taskId=<id>} ends on it (FR3 of
 * harden-logging-observability).
 *
 * <p>Takes the runner's own {@link Logger} rather than declaring one, so every line stays
 * attributed to the slot class an operator greps for.
 *
 * <p>Implements FR3, FR9 of harden-logging-observability.
 */
final class SlotOutcomeLog {

    private final Logger log;

    /**
     * @param log the slot runner's own logger, so lines are attributed to the slot; never null
     */
    SlotOutcomeLog(Logger log) {
        this.log = log;
    }

    /**
     * The per-outcome <em>detail</em> line: the free text each terminal variant carries (a delivery
     * summary, a park report, an abort cause) which the canonical summary's fixed vocabulary has no
     * room for.
     *
     * <p>The four variants that produce a summary log their detail at DEBUG (task 4.3 of
     * harden-logging-observability). Each used to state the outcome here at its own level, which
     * with the summary now stating it at the level the outcome warrants would be two lines saying
     * the same thing about one task — and for an infrastructure abort, a third one under
     * {@code AbortHandler}'s own WARN/ERROR naming the cause. One outcome, one level-bearing line:
     * the summary. {@code Skipped} keeps its WARN because no summary is written for it — nothing
     * ran, yet an operator still wants to know the slot declined the task.
     *
     * @param claimed the task the slot ran; never null
     * @param result the terminal result of that run; never null
     */
    void detail(TaskRef claimed, TakeResult result) {
        switch (result) {
            case TakeResult.Delivered delivered ->
                log.debug("slot for task {} delivered: {}", claimed.id(), delivered.summary());
            case TakeResult.AwaitingHuman awaitingHuman ->
                log.debug(
                        "slot for task {} parked ({}): {}",
                        claimed.id(),
                        awaitingHuman.reason(),
                        awaitingHuman.report());
            case TakeResult.Aborted aborted -> log.debug("slot for task {} aborted: {}", claimed.id(), aborted.cause());
            case TakeResult.Revoked revoked -> log.debug("slot for task {} revoked: {}", claimed.id(), revoked.note());
            case TakeResult.Skipped skipped ->
                log.warn(
                        OperatorEvent.SLOT_SKIPPED.head() + "slot for task {} skipped: {}",
                        claimed.id(),
                        skipped.reason());
            case TakeResult.EmptyQueue _ ->
                log.debug("slot for task {} reported an unexpected empty-queue result", claimed.id());
        }
    }

    /**
     * FR3: the canonical task summary, emitted last so a {@code grep taskId=<id>} ends on it.
     * {@code EmptyQueue}/{@code Skipped} assemble to no summary — no run happened, so there is
     * nothing to summarize (the same boundary the ledger draws).
     *
     * @param result the terminal result to summarize; never null
     * @param wall the slot's wall time; never null
     */
    void summarize(TakeResult result, Duration wall) {
        TaskSummary summary = TaskSummaryAssembler.assemble(result, wall);
        if (summary != null) {
            AnchorLog.taskSummary(summary);
        }
    }

    /**
     * The crash boundary's pair of lines: the classification, then the one summary a task leaving
     * through that boundary still gets (FR3 — the grep story must not simply stop).
     *
     * <p>FR9: a slot dying because the daemon is stopping is not a fault of the slot's. During the
     * shutdown phase the round it was in was interrupted on purpose, so the death is recorded once
     * at WARN, naming the exception type but carrying no stack — the stack would describe the stop,
     * not a defect. Outside the phase nothing changed: an uncaught crash is still an ERROR with its
     * full stack.
     *
     * <p>The summary states the outcome and the wall time and claims nothing else: the facts a
     * terminal result would have carried are exactly what the crash destroyed, so it names no
     * stage, no attempts, and no token totals it cannot know.
     *
     * @param claimed the task whose slot crashed; never null
     * @param crash the throwable caught at the slot boundary; never null
     * @param wall the slot's wall time up to the crash; never null
     */
    void crashed(TaskRef claimed, Throwable crash, Duration wall) {
        if (ShutdownPhase.inProgress()) {
            // throwable-not-subject: the stop is the cause, and the classification is the whole
            //     content of the line — a stack here would be noise on every clean shutdown.
            log.warn(
                    OperatorEvent.SLOT_STOPPED_BY_SHUTDOWN.head()
                            + "slot for task {} stopped by the daemon shutdown ({})",
                    claimed.id(),
                    crash.getClass().getSimpleName());
        } else {
            log.error(
                    OperatorEvent.SLOT_CRASHED_UNCAUGHT.head() + "slot for task {} crashed uncaught",
                    claimed.id(),
                    crash);
        }
        AnchorLog.taskSummary(new TaskSummary(TaskSummary.Outcome.ABORTED, null, null, 0, wall, Map.of()));
    }
}
