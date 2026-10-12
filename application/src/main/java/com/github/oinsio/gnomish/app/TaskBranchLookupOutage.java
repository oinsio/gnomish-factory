package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The decision a take makes when its task-branch lookup could not reach origin: a claim-time fetch
 * outage, so the claim is released plain through {@link ClaimOutageRelease}, outside the abort
 * accounting, as a base-refresh outage is (FR9 of add-base-ref-resolution). Before finding F6 of
 * tighten-spec-contract the lookup's {@link BranchLocationUnavailableException} reached the
 * crash-abort protocol, whose counted abort charged the daemon's outage to the task.
 *
 * <p>{@link ClaimedRunFailureDispatch} classifies the exception — by its cause, whichever step met
 * it — and hands it here; this class writes the one WARN of the deciding layer, and the ERROR of a
 * release that itself failed.
 *
 * <p>Implements FR9 of add-base-ref-resolution; FR6 of harden-task-branch-contract.
 */
final class TaskBranchLookupOutage {

    private static final Logger log = LoggerFactory.getLogger(TaskBranchLookupOutage.class);

    private TaskBranchLookupOutage() {}

    /**
     * Releases the claim on {@code ref} for a lookup origin never answered.
     *
     * @param tracker the tracker the claim is held through
     * @param ref the claimed task
     * @param outage what the lookup threw
     * @return the released-on-outage result the take ends on
     */
    static TakeResult release(Tracker tracker, TaskRef ref, BranchLocationUnavailableException outage) {
        // throwable-not-subject: the outage is a classified condition, not a defect; its reason is
        // the subject, and the stack would only name the lookup that met it.
        log.warn(
                OperatorEvent.TASK_BRANCH_LOOKUP_UNAVAILABLE.head()
                        + "releasing claim on task {}: origin never answered the lookup of its task branch: {}",
                ref.id(),
                outage.reason().forLog());
        return ClaimOutageRelease.release(
                tracker,
                ref,
                "the lookup of its task branch",
                outage.reason(),
                unreleased -> log.error(
                        OperatorEvent.TASK_BRANCH_LOOKUP_RELEASE_FAILED.head()
                                + "release failed for task {} whose task-branch lookup could not reach origin",
                        ref.id(),
                        unreleased));
    }
}
