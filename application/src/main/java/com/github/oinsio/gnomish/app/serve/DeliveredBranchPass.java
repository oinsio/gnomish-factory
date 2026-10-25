package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.ClaimlessDeliveredCleanup;
import com.github.oinsio.gnomish.app.OperatorPreconditionRefusals;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.app.port.git.TaskListRow;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.logtext.MdcAwareThread;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worktree janitor's branch pass (FR4, FR11, FR12 of fix-terminal-receipt-convergence, design
 * D2, D5, D13; proposal Q1: every tick): over the clone's {@code gnomish/*} branches, as the local
 * listing classifies them, it forwards exactly the branches the domain's forwarding table marks
 * ({@code BranchShape#forwardedByBranchPass}) to the claimless cleanup owner as a task ref; the
 * owner reads each through the fetching locate before it judges, and reads the tracker only for a
 * {@code CompletedUncleaned} tip. A branch the table does not forward costs no fetch and no tracker
 * read, and is one DEBUG line naming its shape (NFR-C2, NFR-O2). No claim is written or checked.
 *
 * <p>A task occupying a slot of this instance is skipped: its own slot is finishing it, and two
 * writers on one worktree race on git's own locks rather than on the store's compare-and-swap.
 *
 * <p>A branch still {@code Unregistered} after the owner's read is written nothing and named in one
 * WARN per pass with the kind of location the locate answered — a local head, which the locate does
 * not fetch (NG5), or a tracking ref for which origin delivered no register or could not be reached
 * — never asserting which cause holds, since the locate cannot tell them apart (FR12). The next
 * tick names it again until the quarantine owner takes it.
 *
 * <p>A failure stops at its branch: the others are still visited, and one WARN per pass names
 * every branch that failed, with the first failure's throwable (each one is a DEBUG line with its
 * own); the next tick tries again. A listing that could not run at all fails the tick, which is the
 * supervised loop's to report.
 *
 * <p>An operator precondition refusal (design D21 of add-sandbox-base-image) — the clone's ref
 * format, the container runtime, the image contract, the box's git floor, met by the listing or by
 * a branch's cleanup — is no branch failure: it is reported to the daemon's conditions, whose
 * opening WARN is the operator's line, and the pass goes on. While any precondition condition is
 * open the pass waits, with one DEBUG line naming it, since every forwarded branch would meet the
 * same check again.
 *
 * <p>Implements FR4, FR11, FR12, UX2, NFR-C2, NFR-O2 of fix-terminal-receipt-convergence; FR17 of
 * add-sandbox-base-image.
 */
public final class DeliveredBranchPass implements BranchPass {

    private static final Logger log = LoggerFactory.getLogger(DeliveredBranchPass.class);

    private final Path cloneDir;
    private final TaskBranchGit branches;
    private final ClaimlessDeliveredCleanup cleanup;
    private final PipelineDefinition definition;
    private final DaemonConditions conditions;

    /**
     * @param cloneDir the clone whose task branches are listed; never null
     * @param branches the task-branch port the listing comes from; never null
     * @param cleanup the claimless cleanup owner, which reads the tracker for the branches it is
     *     handed; never null
     * @param definition the daemon's pipeline, whose bindings select the cleanup's medium; never null
     * @param conditions the daemon's conditions a precondition refusal is reported to; never null
     */
    public DeliveredBranchPass(
            Path cloneDir,
            TaskBranchGit branches,
            ClaimlessDeliveredCleanup cleanup,
            PipelineDefinition definition,
            DaemonConditions conditions) {
        this.cloneDir = cloneDir;
        this.branches = branches;
        this.cleanup = cleanup;
        this.definition = definition;
        this.conditions = conditions;
    }

    @Override
    public void run(Set<String> heldKeys) {
        Set<DaemonConditionKind> held = conditions.openPreconditions();
        if (!held.isEmpty()) {
            log.debug("worktree janitor: branch pass waits while daemon condition(s) {} are open", held);
            return;
        }
        List<TaskListRow> rows;
        try {
            rows = branches.list(cloneDir);
        } catch (RuntimeException e) {
            if (reported(e)) {
                return;
            }
            throw e;
        }
        List<String> failed = new ArrayList<>();
        List<String> unregistered = new ArrayList<>();
        @Nullable RuntimeException firstFailure = null;
        for (TaskListRow row : rows) {
            String taskId = row.taskId();
            if (!row.shape().forwardedByBranchPass()) {
                log.debug(
                        "worktree janitor: {} not forwarded: its tip is {}",
                        taskId,
                        row.shape().label());
                continue;
            }
            RuntimeException failure = convergeScoped(taskId, heldKeys, unregistered);
            if (failure != null) {
                failed.add(taskId);
                firstFailure = firstFailure == null ? failure : firstFailure;
            }
        }
        if (!unregistered.isEmpty()) {
            log.warn(
                    OperatorEvent.WORKTREE_JANITOR_BRANCH_UNREGISTERED.head()
                            + "worktree janitor: still without a factory register after the touchpoint read,"
                            + " left to the quarantine owner: {}",
                    unregistered);
        }
        if (firstFailure != null) {
            log.warn(
                    OperatorEvent.WORKTREE_JANITOR_BRANCH_CLEANUP_FAILED.head()
                            + "worktree janitor: delivered cleanup failed for {}; retried next tick",
                    failed,
                    firstFailure);
        }
    }

    /**
     * One branch under its task's MDC scope — its failure line included; a branch still
     * unregistered after the owner's read is added to {@code unregistered}. The failure, or null.
     */
    private @Nullable RuntimeException convergeScoped(String taskId, Set<String> heldKeys, List<String> unregistered) {
        try (var ignored = MdcAwareThread.taskScope(taskId)) {
            try {
                converge(taskId, heldKeys).ifPresent(location -> unregistered.add(named(taskId, location)));
                return null;
            } catch (RuntimeException e) {
                if (reported(e)) {
                    return null;
                }
                log.debug("worktree janitor: delivered cleanup of {} failed", taskId, e);
                return e;
            }
        }
    }

    /**
     * Reports {@code failure} to the daemon's conditions when it is an operator precondition
     * refusal (design D21); the condition's WARN is its line, so this one is DEBUG.
     *
     * @return whether {@code failure} was such a refusal
     */
    private boolean reported(RuntimeException failure) {
        Optional<TakeResult.OperatorPreconditionUnmet> refusal = OperatorPreconditionRefusals.unmet(failure);
        refusal.ifPresent(unmet -> {
            // throwable-not-subject: the refusal is a precondition, not a defect; its condition
            //     carries the message and the fix.
            log.debug("worktree janitor: precondition {} refused; reported to its condition", unmet.kind());
            conditions.report(unmet);
        });
        return refusal.isPresent();
    }

    private Optional<BranchLocation> converge(String taskId, Set<String> heldKeys) {
        if (heldKeys.contains(TaskIdSanitizer.sanitize(taskId))) {
            log.debug("worktree janitor: {} occupies a slot of this instance; its slot finishes it", taskId);
            return Optional.empty();
        }
        return cleanup.converge(cloneDir, new TaskRef(taskId), definition);
    }

    /** The branch and the kind of location the owner's locate answered — never the cause. */
    private static String named(String taskId, BranchLocation location) {
        return taskId
                + (location instanceof BranchLocation.Local
                        ? " (local head, not fetched)"
                        : " (tracking ref: origin delivered no register or could not be reached)");
    }
}
