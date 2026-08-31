package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.branch.BranchQuarantineException;
import com.github.oinsio.gnomish.app.branch.BranchQuarantineReport;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The quarantine arm of a claimed take run (FR15 of harden-task-branch-contract): a branch that
 * classifies to one of the three shapes no automatic recovery can converge parks the task for a
 * human on that FIRST classification, with the diagnosis in the report — and spends no attempt of
 * the unified recovery accounting doing it.
 *
 * <p>This is deliberately NOT the crash arm ({@link TakeCrashAbort}). Routing a quarantine through
 * {@code recordAbort} would count an attempt against a branch whose next read is guaranteed to be
 * identical, so the task would return to {@code Ready}, be claimed again, classify the same way,
 * and burn the fuse one pickup at a time — the crash loop UX2 names, arriving at the same park K
 * pickups late and with the diagnosis buried under K identical aborts. Stopping here reaches the
 * same terminal state immediately, and the attempts already on record are quoted in the report
 * rather than added to.
 *
 * <p>The park is best-effort in the same shape as {@link AbortHandler}'s tracker writes (NFR-R2): a
 * tracker that cannot be written to must not turn the quarantine into an escaping exception — the
 * run has decided to stop for a human either way, and the report travels back in the result.
 *
 * <p>Implements FR15, NFR-O2, UX2 of harden-task-branch-contract.
 */
public final class TakeQuarantinePark {

    private static final Logger log = LoggerFactory.getLogger(TakeQuarantinePark.class);

    private TakeQuarantinePark() {}

    /**
     * Parks {@code trackerTask} as {@code AwaitingHuman(INFRA)} with the quarantine report.
     *
     * @param definition the running pipeline; its first stage names the last structurally-known
     *     position reported in the result — a quarantine never entered a stage; never null
     * @param trackerTask the claimed task whose branch cannot be recovered; its already-fetched
     *     accounting is quoted in the report, so no extra tracker read is paid for; never null
     * @param tracker the tracker port for the best-effort park; never null
     * @param quarantine the classification that stopped the run; never null
     * @return the terminal {@link TakeResult.AwaitingHuman} carrying the report
     */
    public static TakeResult onQuarantine(
            PipelineDefinition definition,
            TrackerTask trackerTask,
            Tracker tracker,
            BranchQuarantineException quarantine) {
        TaskRef ref = trackerTask.ref();
        String report =
                BranchQuarantineReport.of(trackerTask.snapshot().id(), quarantine.shape(), trackerTask.abortFacts());
        log.error("Quarantining task {}: {}", ref.id(), quarantine.getMessage());
        parkBestEffort(tracker, ref, report);
        TaskState finalState = TaskState.atStageStart(definition.stages().get(0).name());
        return new TakeResult.AwaitingHuman(finalState, ParkReason.INFRA, report);
    }

    private static void parkBestEffort(Tracker tracker, TaskRef ref, String report) {
        try {
            tracker.park(ref, ParkReason.INFRA, report);
        } catch (RuntimeException unparked) {
            log.error("park(INFRA) failed for quarantined task {}; stopping for a human anyway", ref.id(), unparked);
        }
    }
}
