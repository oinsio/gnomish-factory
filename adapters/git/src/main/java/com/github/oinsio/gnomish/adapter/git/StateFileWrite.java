package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.EgressCursorDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.atomicfile.AtomicFileWriter;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lifecycle store's writes of {@code state.json} (FR3, FR4 of harden-task-branch-contract):
 * the synthesized initial state at STARTED, and the attempt-counter reset a decision implies at
 * RESUMED. Every other write of that file is {@link GitAttemptPersistence}'s — the one-writer rule
 * of the state directory holds, with this narrow lifecycle exception named here.
 *
 * <p>The file is written but not committed: the {@code git add -A} of the lifecycle commit that
 * follows picks it up, which is what makes the two envelopes one transition rather than two. The
 * write itself goes through the shared {@link AtomicFileWriter}, so a kill mid-write leaves the
 * complete previous content behind — but that atomicity covers this one file, not the transition.
 *
 * <p>The transition's durability point is the lifecycle commit (and its push), per the per-medium
 * table of {@code docs/adr/0003-crash-consistency.md}. The kill window between this write and that
 * commit therefore freezes an uncommitted worktree file over an unchanged branch tip, and the next
 * pickup classifies that tip — which still carries the shape it had before the transition began.
 * The two windows differ, so neither claim covers both:
 *
 * <ul>
 *   <li><b>RESUMED</b> — the tip still carries the previous envelope pair, so the pickup resumes
 *       and re-drives the same lifecycle write. The state written here is synthesized from the same
 *       inputs the re-drive uses, so the orphan and the re-driven file carry the same value:
 *       recovery is a plain roll-forward and running it twice equals running it once.
 *   <li><b>STARTED</b> — the tip carries no envelope at all, which classifies as {@code
 *       BranchShape.Bare} and routes to a fresh claim. In a clone that never saw the branch (it was
 *       never pushed) that starts the task over; in this same clone the local branch is still there
 *       and {@link TaskBranchCreator} answers {@code AlreadyExists}, so the take fails rather than
 *       committing the orphan.
 * </ul>
 *
 * <p>Either way the orphan does not reach a commit by itself, and since FR2 of fix-envelope-medium
 * it is read by nobody: the resume path ({@code TaskStoreGit#readRecordedState}) and the cursor
 * carry-forward below both resolve at the tip, and salvage restores factory-owned paths from the
 * tip rather than from a dirty worktree (FR5).
 *
 * <p>Split out of {@link GitTaskRepository}, which keeps {@code task.json}: the two envelopes have
 * different writers and different rules, and this one also owns the denial-cursor carry-forward
 * below, so the split moves a responsibility rather than only line count.
 *
 * <p>Implements FR3, FR4, FR5 of harden-task-branch-contract; FR2 of fix-envelope-medium.
 */
final class StateFileWrite {

    private static final Logger log = LoggerFactory.getLogger(StateFileWrite.class);

    private StateFileWrite() {}

    /**
     * Writes {@code state} as {@code .gnomish-task/state.json} in {@code worktree}.
     *
     * @param runner the git subprocess runner the cursor's tip read goes through
     * @param worktree the task worktree holding the state directory
     * @param taskId the task whose state is written; for error reporting
     * @param state the state to record
     * @param event the lifecycle event this write belongs to; for error reporting
     */
    static void write(
            GitProcessRunner runner, Path worktree, String taskId, TaskState state, TaskLifecycleEvent event) {
        Path target = worktree.resolve(EnvelopePaths.STATE_JSON_PATH);
        try {
            AtomicFileWriter.write(
                    target,
                    TaskStateJson.mapper()
                            .writeValueAsString(StateJsonMapper.toDto(state, currentCursor(runner, worktree))));
        } catch (IOException e) {
            throw new GitTaskRepositoryException(taskId, event, "writing state.json", e);
        }
    }

    /**
     * The denial cursor the file being rewritten already carries, carried forward unchanged (FR5 of
     * fix-denial-attribution-durability). A lifecycle rewrite reads no denial source, so it has no
     * position of its own; regenerating the file without one would erase what the last attempt
     * committed. Host mode never writes a cursor itself — it has no egress guard — but a branch
     * that ran in container mode before this resume carries one.
     *
     * <p>Best-effort for the two answers a finished read can give: an absent or unparseable file
     * yields none and the write proceeds cursorless, costing the next run a full re-read of the
     * guard's log tail rather than a denial. A read that never ran to its own exit gives neither
     * answer — it establishes nothing — so {@link BranchTipUnavailableException} passes through
     * rather than being read as "the tip carries no cursor" (NFR-R2 of fix-envelope-medium).
     * Degrading there would regenerate the file without the position a container-mode run
     * committed, and the lifecycle commit that follows would make that erasure durable; the
     * refusal instead aborts a transition that is restartable, since the write has not happened.
     *
     * <p>The position is read at the worktree's {@code HEAD}, not from its working copy (FR2,
     * design D1 of fix-envelope-medium): the file on disk is the staging area for the very write
     * below, so a stale or half-written one there must not be what a lifecycle commit carries
     * forward.
     *
     * <p>Kept in sync with {@link TaskLifecycleCommitWriter#tipStateCursor}: both media read the
     * cursor from the tip's {@code state.json} and carry it into the regenerated file, and both
     * degrade to no cursor — never to a failure — when the committed document answers that there is
     * none to carry, and both refuse — never degrade — when the read never ran to its own exit.
     * The two name that outcome by different types, because the media classify it at different
     * seams: {@link BranchTipUnavailableException} from the subprocess gate here, {@code
     * GitObjectsInterruptedException} from the bare-object reader there — which has no deadline, so
     * an interrupt is the only way it arises. The two reach the same commit by different mechanisms (this one a {@code git show}
     * subprocess, that one a blob through the bare-object reader), so no shared implementation is
     * available to enforce it.
     */
    private static @Nullable EgressCursorDto currentCursor(GitProcessRunner runner, Path worktree) {
        try {
            return new GitShowTip(runner, worktree, GitObjects.HEAD)
                    .readAtTip(EnvelopePaths.STATE_JSON_PATH)
                    .map(StateJsonMapper::readDto)
                    .map(StateJsonDto::egressCursor)
                    .orElse(null);
        } catch (BranchTipUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.debug(
                    "no recorded denial cursor to carry forward from HEAD of {}: absent or unreadable;"
                            + " the next run reads its denial source from the start",
                    worktree,
                    e);
            return null;
        }
    }
}
