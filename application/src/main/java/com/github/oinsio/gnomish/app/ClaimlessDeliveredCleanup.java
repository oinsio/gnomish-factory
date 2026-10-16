package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.LocalTaskBranch;
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The claimless route to {@link DeliveredCleanup} (FR4, design D2, D5, D13 of
 * fix-terminal-receipt-convergence): a caller that holds no claim — {@code take <ref>} on a finished
 * task, the {@code serve} janitor's branch pass — hands the task here, and the one claimless
 * sequence runs over a branch the clone may hold with or without its register:
 *
 * <ol>
 *   <li><b>locate</b> — the task-branch port's {@link TaskBranchGit#locate}, whose touchpoint fetch
 *       lands a tracking-only branch and its register in place; any answer but a local or a
 *       remote-tracking branch (never created, merged and deleted, origin unanswered, refused)
 *       leaves nothing here to clean, so the mode is never planned for a task that has no branch;
 *   <li><b>classify at the register</b> over that same location ({@link TaskBranchGit#classifyShape(
 *       Path, String, BranchLocation)}), so the decision, the adoption and the caller's report rest
 *       on one lookup and one touchpoint fetch (NFR-C2);
 *   <li><b>read the tracker</b> only for a {@code CompletedUncleaned} tip — the janitor's task ref
 *       costs one {@code fetchTask}; the {@code take} arms hand the task they already observed and
 *       cost none;
 *   <li><b>adopt</b> over the location ({@link TaskBranchGit#adopt}, design D9, D11), so the cleanup
 *       commit lands on a head that exists;
 *   <li><b>clean</b> through {@link DeliveredCleanup#cleanUpIfFinished}, over the medium the same
 *       {@link SandboxModeSelector} plan the claimed routes take ({@code TakeWorkRouter}) selects —
 *       host mode through the registered clone's worktree store and disposal, container mode through
 *       the support the slot's container factory builds, so a container task's branch is never
 *       checked out on the host. A refused plan propagates: the caller decides what a failed
 *       cleanup means for it.
 * </ol>
 *
 * <p>A tip still {@code Unregistered} after the locate is returned with the location the locate
 * answered, and nothing is written to it: an adoption would make a local head without its register,
 * the state no later locate refetches (NG5). The caller names it; the quarantine owner keeps it
 * (FR12). Every other tip is left alone with one DEBUG line.
 *
 * <p>Implements FR4, FR8, FR11, FR12, NFR-C2 of fix-terminal-receipt-convergence.
 */
public final class ClaimlessDeliveredCleanup {

    private static final Logger log = LoggerFactory.getLogger(ClaimlessDeliveredCleanup.class);

    private final TaskGit git;
    private final RegisteredClone clone;
    private final ContainerTakeSupport containers;
    private final List<String> credentialEnvVarsToScrub;
    private final Tracker tracker;

    /**
     * @param git the task-git bundle: the branch port the location is asked of and the host medium's
     *     store and disposal; never null
     * @param clone the registered clone the host medium's worktrees live under; never null
     * @param containers the execution-mode selector and the container support factory; never null
     * @param credentialEnvVarsToScrub the declared credential names a container support is built
     *     with; names only, never null
     * @param tracker the tracker a task ref's finish is read from, once per {@code
     *     CompletedUncleaned} tip; never null
     */
    public ClaimlessDeliveredCleanup(
            TaskGit git,
            RegisteredClone clone,
            ContainerTakeSupport containers,
            List<String> credentialEnvVarsToScrub,
            Tracker tracker) {
        this.git = git;
        this.clone = clone;
        this.containers = containers;
        this.credentialEnvVarsToScrub = List.copyOf(credentialEnvVarsToScrub);
        this.tracker = tracker;
    }

    /**
     * The slot's claimless cleanup, from explicit reads of its wiring.
     *
     * @param wiring the slot's equipment; never null
     * @param tracker the tracker a task ref's finish is read from; never null
     * @return the claimless cleanup over the slot's git, clone and container seam; never null
     */
    public static ClaimlessDeliveredCleanup forSlot(SlotWiring wiring, Tracker tracker) {
        return new ClaimlessDeliveredCleanup(
                wiring.git(),
                wiring.registeredClone(),
                wiring.containerTakeSupport(),
                wiring.credentialEnvVarsToScrub(),
                tracker);
    }

    /**
     * The sequence for a task known only by its ref: the tracker is read once, and only when the
     * tip is {@code CompletedUncleaned}.
     *
     * @param cloneDir the clone the task branch is looked up in; never null
     * @param ref the task whose branch is converged; its id names the branch; never null
     * @param definition the pipeline whose bindings select the execution mode; never null
     * @return the location the locate answered when the tip is still {@code Unregistered} after it,
     *     else empty; never null
     */
    public Optional<BranchLocation> converge(Path cloneDir, TaskRef ref, PipelineDefinition definition) {
        return converge(cloneDir, ref.id(), () -> tracker.fetchTask(ref), definition);
    }

    /**
     * The sequence for a task the caller already observed: no tracker read of its own.
     *
     * @param cloneDir the clone the task branch is looked up in; never null
     * @param observed the task as the caller's own {@code fetchTask} reported it; never null
     * @param definition the pipeline whose bindings select the execution mode; never null
     * @return the location the locate answered when the tip is still {@code Unregistered} after it,
     *     else empty; never null
     */
    public Optional<BranchLocation> converge(Path cloneDir, TrackerTask observed, PipelineDefinition definition) {
        return converge(cloneDir, observed.snapshot().id(), () -> observed, definition);
    }

    private Optional<BranchLocation> converge(
            Path cloneDir, String taskId, Supplier<TrackerTask> finishRead, PipelineDefinition definition) {
        TaskBranchGit branches = git.branches();
        BranchLocation location = branches.locate(cloneDir, taskId);
        if (!(location instanceof BranchLocation.Local || location instanceof BranchLocation.RemoteTracking)) {
            log.debug("delivered cleanup of {} skipped: the clone holds no task branch ({})", taskId, location);
            return Optional.empty();
        }
        BranchShape tip = branches.classifyShape(cloneDir, taskId, location);
        switch (tip) {
            case BranchShape.CompletedUncleaned() -> clean(cloneDir, taskId, location, finishRead.get(), definition);
            case BranchShape.Unregistered _ -> {
                log.debug("delivered cleanup of {} left: still without its register after the locate", taskId);
                return Optional.of(location);
            }
            case BranchShape.Bare(),
                    BranchShape.Created(),
                    BranchShape.InProgress(),
                    BranchShape.AwaitingApproval(),
                    BranchShape.Parked(),
                    BranchShape.Answered(),
                    BranchShape.Delivered(),
                    BranchShape.UnsupportedVersion _,
                    BranchShape.Corrupt _,
                    BranchShape.Unknown _ ->
                log.debug("delivered cleanup of {} is a no-op: the tip is {}", taskId, tip.label());
        }
        return Optional.empty();
    }

    /** Adopts over the location the lookup just refreshed, before anything writes, then cleans. */
    private void clean(
            Path cloneDir,
            String taskId,
            BranchLocation location,
            TrackerTask observed,
            PipelineDefinition definition) {
        // Design D9, D11: a branch held only as a remote-tracking ref becomes local through the one
        // owner. Over these two locations the owner answers a local branch; Nowhere is not among them.
        if (git.branches().adopt(cloneDir, taskId, location) instanceof LocalTaskBranch branch) {
            medium(cloneDir, taskId, definition).cleanUpIfFinished(cloneDir, branch, observed);
        }
    }

    private DeliveredCleanup medium(Path cloneDir, String taskId, PipelineDefinition definition) {
        var plan = containers.modeSelector().plan(definition, clone);
        return switch (plan.mode()) {
            case HOST -> DeliveredCleanup.host(git, clone);
            case CONTAINER ->
                DeliveredCleanup.container(
                        git.branches(),
                        containers
                                .containerSupportFactory()
                                .create(cloneDir, taskId, plan.segments(), definition, credentialEnvVarsToScrub));
        };
    }
}
