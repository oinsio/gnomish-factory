package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import java.nio.file.Path;

/**
 * The host medium's read of {@code task.json} for a transition that <em>requires</em> the envelope
 * to be there — the one owner of that read for both host-side rewrites: the lifecycle rewrites in
 * {@link GitTaskRepository} and the terminal-write receipt in {@link TerminalWriteMarker}.
 *
 * <p>The read resolves at the worktree's {@code HEAD}, not at its working copy (FR2, design D1 of
 * fix-envelope-medium): the file on disk is where the <em>next</em> envelope version is staged, so
 * a stale or half-written one there must not be carried forward into a lifecycle commit.
 *
 * <p>Unlike a classifier's read, a non-zero exit here is never read as absence: a revision with no
 * such path, an unborn {@code HEAD} and a corrupt object all end this way and only git can say
 * which, so the refusal carries git's own words as the exception's detail rather than leaving them
 * to a DEBUG line. Absence itself is still a fault on this path — every transition that rewrites
 * the envelope runs on a branch that carries one.
 *
 * <p>Implements FR2 of fix-envelope-medium; FR5 of harden-logging-observability.
 */
final class RequiredTaskJson {

    private RequiredTaskJson() {}

    /**
     * @param runner the git subprocess runner the tip read goes through
     * @param worktree the task worktree whose {@code HEAD} carries the envelope
     * @param taskId the task whose envelope is read; for error reporting
     * @param event the lifecycle write this read serves; for error reporting
     * @return the branch's current {@code task.json} as its raw wire DTO
     * @throws GitTaskRepositoryException if the tip read exits non-zero, carrying git's own
     *     diagnosis of why
     */
    static TaskJsonDto atTipOf(GitProcessRunner runner, Path worktree, String taskId, TaskLifecycleEvent event) {
        GitCommandResult result =
                new GitShowTip(runner, worktree, GitObjects.HEAD).showAtTip(EnvelopePaths.TASK_JSON_PATH);
        if (result.exitCode() != 0) {
            throw new GitTaskRepositoryException(
                    taskId,
                    event,
                    "reading " + EnvelopePaths.TASK_JSON_PATH + " at HEAD of " + worktree + ", git show exited "
                            + result.exitCode(),
                    result.stderr());
        }
        return TaskJsonMapper.readDto(result.stdout());
    }
}
