package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;

/**
 * The <em>take order</em>: a {@link RunOrder} plus what only a tracker-driven invocation has — the
 * claimed task, the tracker it was claimed through, and this instance's identity (design D1 of
 * introduce-take-order). Manual runs have no tracker and never carry one.
 *
 * <p>The single owner of task identity on the take chain (D2): {@link #ref()} and {@link
 * #taskId()} are the only derivations of the claimed task's identity; no consumer re-derives
 * either from the {@link TrackerTask} it could reach through this order.
 *
 * <p>Built only once the claimed task has been fetched — no earlier site of the chain has a task
 * to put in it, and a take order never holds a null task.
 *
 * <p>Implements FR2, FR3, D6 of introduce-take-order.
 *
 * @param run the mode-independent half of the order
 * @param trackerTask the claimed task as {@code fetchTask} returned it
 * @param tracker the tracker the task was claimed through
 * @param instanceId the identity this factory instance holds the claim under
 */
public record TakeOrder(RunOrder run, TrackerTask trackerTask, Tracker tracker, InstanceId instanceId) {

    /** The claimed task's canonical identity, as the tracker port knows it. */
    public TaskRef ref() {
        return trackerTask.ref();
    }

    /**
     * The claimed task's id as its frozen snapshot records it — the name the task branch, the
     * worktree and every report use.
     */
    public String taskId() {
        return trackerTask.snapshot().id();
    }

    /**
     * This order re-bound to the task's own law: the same task, tracker and identity, with {@code
     * taskDefinition} in place of the startup definition the order was built with (design D6 of
     * introduce-take-order).
     *
     * <p>The one place an order's definition changes. Its only callers are the two fresh-claim
     * {@code claimAt} sites ({@link TakeFreshClaim}, {@link TakeContainerFreshClaim}): once {@link
     * TaskTierLaw} has read the definition at the task's resolved base, everything downstream runs
     * under that definition, so each site hands on only the re-bound copy and stops using the
     * order it was given.
     *
     * @param taskDefinition the definition read from the task's resolved base; never null
     */
    TakeOrder withDefinition(PipelineDefinition taskDefinition) {
        return new TakeOrder(run.withDefinition(taskDefinition), trackerTask, tracker, instanceId);
    }
}
