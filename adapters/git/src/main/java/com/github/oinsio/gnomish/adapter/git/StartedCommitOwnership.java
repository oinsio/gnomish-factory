package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.MalformedStateFileException;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException;
import com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Decides whether a STARTED commit found on a revision's first-parent line is that task's own
 * (design D11 of fix-operator-blockers): the {@code task.json} the commit carries must name the
 * task whose branch the revision is.
 *
 * <p>The nearest STARTED commit alone does not settle it. A base that fast-forwards or
 * rebase-merges an earlier task puts that task's STARTED and cleanup commits on its own
 * first-parent line, and a branch with no STARTED commit of its own then points at the very
 * commit the earlier task's branch points at — no fact about the commit tells the two apart, only
 * the branch name does.
 *
 * <p>The branch is read from the revision's full ref name ({@code git rev-parse
 * --symbolic-full-name}): {@code refs/heads/gnomish/<id>} or {@code refs/remotes/origin/gnomish/<id>},
 * whether the reader named the branch or stands on it through a worktree's {@code HEAD}. A
 * sanitized task id holds no {@code /}, so the trailing {@code gnomish/<id>} is exactly one branch.
 * A revision that names no ref (a commit id, a detached {@code HEAD}) owns no STARTED commit, and
 * neither does a STARTED commit whose {@code task.json} is absent or unreadable: both answer "not
 * delivered", the direction that leaves the branch to the rest of its classification rather than
 * skipping its work.
 *
 * <p>Implements FR14 of fix-operator-blockers, with its NFR-R4.
 */
final class StartedCommitOwnership {

    private StartedCommitOwnership() {}

    /**
     * @param fullRefName the full ref name the revision resolves to, empty when it names none
     * @param taskJson the {@code task.json} the STARTED commit carries, empty when it carries none
     * @return whether that document names the task whose branch {@code fullRefName} is
     */
    static boolean ownedBy(String fullRefName, Optional<UntrustedText> taskJson) {
        return taskJson.flatMap(StartedCommitOwnership::branchOf)
                .map(branch -> fullRefName.endsWith("/" + branch))
                .orElse(false);
    }

    private static Optional<String> branchOf(UntrustedText taskJson) {
        try {
            return Optional.of(
                    TaskIdSanitizer.branchName(TaskJsonMapper.readDto(taskJson).taskId()));
        } catch (UncheckedIOException
                | UnsupportedStateFileVersionException
                | MalformedStateFileException
                | InvalidTaskIdException e) {
            return Optional.empty();
        }
    }
}
