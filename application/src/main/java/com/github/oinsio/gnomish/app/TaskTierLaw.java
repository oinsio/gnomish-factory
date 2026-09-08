package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.pipeline.BoundTaskTier;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.BaseLawReport;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-task law read of a fresh claim (FR13, UX5, design D14 of add-base-ref-resolution): the
 * task tier of the definition is read from the law binding the task runs under, and it — never the
 * startup definition — is what the task is synthesized from, created with, and run under. The one
 * step both ends of the fresh-claim pair ({@link TakeFreshClaim}, {@link TakeContainerFreshClaim})
 * share, so the rule lives once.
 *
 * <p><b>A base that fails to load parks its task.</b> The failure is deterministic — re-reading the
 * same commit yields the same errors — so it is neither an infrastructure failure in the
 * crash-consistency sense (no claim release into a retry loop) nor a quality failure (no stage
 * attempt burned): the task parks as {@link ParkReason#INFRA} — a pipeline problem that needs a
 * human fix followed by a bare retry, exactly the {@code CannotVerify}/{@code CannotExecute}/
 * {@code PipelineMismatch} shape {@link TakeEscalationExit} already parks that way — never {@link
 * ParkReason#ESCALATION}, which promises a decision reply this park neither expects nor consumes.
 * The report names the base ref, the law commit and every located error, exactly the shape a
 * startup load failure prints. The park is best-effort in the shape of
 * {@link com.github.oinsio.gnomish.app.take.TakeQuarantinePark}: a tracker that cannot be written
 * must not turn a classified stop into an escaping exception, and the report travels back in the
 * result either way. No abort marker is written, so the task's abort facts, backoff and fuse stay
 * as they were.
 *
 * <p>Only the task tier is read: the trusted tier was bound at startup and the assembly exposes no
 * read of it (D15).
 *
 * <p>Implements FR13, UX5 of add-base-ref-resolution.
 */
final class TaskTierLaw {

    private static final Logger log = LoggerFactory.getLogger(TaskTierLaw.class);

    private TaskTierLaw() {}

    /** What the read decided: the task's own definition, or the park that ends the claim. */
    sealed interface Outcome permits Bound, Parked {}

    /**
     * The task tier loaded cleanly.
     *
     * @param definition the definition this task runs under
     * @param lawBinding the binding the run freezes its law from — the peeled law commit, so law
     *     and pin name one SHA by construction
     */
    record Bound(PipelineDefinition definition, LawBinding lawBinding) implements Outcome {}

    /**
     * The base's definition failed to load and the task was parked.
     *
     * @param result the terminal result carrying the report
     */
    record Parked(TakeResult result) implements Outcome {}

    /**
     * Reads the task tier at {@code binding} and either binds the task to it or parks the task.
     *
     * @param assembly the run assembly carrying the invocation's pipeline source; never null
     * @param binding which repository and revision the task's law is read from; never null
     * @param startupDefinition the startup definition, whose first stage names the last
     *     structurally-known position a parked task reports — a park here never entered a stage;
     *     never null
     * @param trackerTask the claimed task; never null
     * @param tracker the tracker port for the best-effort park; never null
     * @return the bound definition, or the park result; never null
     * @throws UncheckedIOException if the law cannot be read at all — an I/O fault, which the
     *     caller's crash arm classifies, never a validation problem
     */
    static Outcome bind(
            RunAssembly assembly,
            LawBinding binding,
            PipelineDefinition startupDefinition,
            TrackerTask trackerTask,
            Tracker tracker) {
        BoundTaskTier taskTier;
        try {
            taskTier = assembly.bindTaskTier(binding);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the pipeline law at " + revisionOf(binding), unreadable);
        }
        return switch (taskTier.outcome()) {
            case LoadOutcome.Loaded(var definition) ->
                new Bound(
                        definition,
                        LawBinding.atRevision(
                                binding.repositoryRoot(), taskTier.lawCommit().hex()));
            case LoadOutcome.Invalid(List<ConfigError> errors) ->
                new Parked(park(binding, taskTier.lawCommit(), errors, startupDefinition, trackerTask, tracker));
        };
    }

    private static TakeResult park(
            LawBinding binding,
            ObjectId lawCommit,
            List<ConfigError> errors,
            PipelineDefinition startupDefinition,
            TrackerTask trackerTask,
            Tracker tracker) {
        TaskRef ref = trackerTask.ref();
        String baseRef = revisionOf(binding);
        String report = BaseLawReport.of(trackerTask.snapshot().id(), baseRef, lawCommit, errors);
        log.error(
                OperatorEvent.TASK_BASE_LAW_INVALID.head()
                        + "parking task {}: the pipeline definition at base {} (law commit {}) fails to load with {}"
                        + " located error(s)",
                ref.id(),
                baseRef,
                lawCommit.hex(),
                errors.size());
        parkBestEffort(tracker, ref, report);
        TaskState finalState =
                TaskState.atStageStart(startupDefinition.stages().getFirst().name());
        return new TakeResult.AwaitingHuman(finalState, ParkReason.INFRA, report);
    }

    private static void parkBestEffort(Tracker tracker, TaskRef ref, String report) {
        try {
            tracker.park(ref, ParkReason.INFRA, report);
        } catch (RuntimeException unparked) {
            log.error(
                    OperatorEvent.BASE_LAW_PARK_FAILED.head()
                            + "park(INFRA) failed for task {} whose base law is invalid; stopping for a human"
                            + " anyway",
                    ref.id(),
                    unparked);
        }
    }

    /** The base ref a report names: the revision a binding was stated with, or the tree it reads. */
    private static String revisionOf(LawBinding binding) {
        return switch (binding) {
            case LawBinding.AtRevision atRevision -> atRevision.revision();
            case LawBinding.WorkingTree workingTree -> "working tree at " + workingTree.repositoryRoot();
        };
    }
}
