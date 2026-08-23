package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

/**
 * The single construction site of the factory's push command (design D2 of fix-lifecycle-push):
 * {@code git push origin <branch>:<branch>} — the exact refspec, never a bare branch name that
 * relies on git's implicit refspec inference, and never {@code --force} /
 * {@code --force-with-lease}, so a non-fast-forward rejection is just a failed push (NFR-S1).
 *
 * <p>Pure primitive: no precondition, no policy, no logging. The command's raw result is handed
 * back to the caller, which owns what a failure means — one WARN and carry on for the best-effort
 * push points ({@link BestEffortPush}, {@link BranchPush}), a re-attempt and a structured verdict
 * for the delivery-verifying ones ({@link RemoteAttemptDelivery}).
 *
 * <p>Implements FR1, NFR-S1 of fix-lifecycle-push.
 */
// Not a record: this is a behavior-bearing command seam (a collaborator, not immutable data),
// kept as a plain final class for parity with its siblings in this package.
@SuppressWarnings("ClassCanBeRecord")
final class RefspecPush {

    private final GitProcessRunner runner;

    RefspecPush(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Pushes {@code branch} to {@code origin} from {@code repo} under the exact refspec
     * {@code branch:branch}.
     *
     * @param repo the clone or worktree the push runs from; never null
     * @param branch the task branch name; never blank
     * @return the raw command result — a non-zero exit code is a normal outcome here
     */
    GitCommandResult push(Path repo, String branch) {
        return runner.run(repo, "push", OriginRemote.NAME, branch + ":" + branch);
    }
}
