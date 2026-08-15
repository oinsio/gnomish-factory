package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.workspace.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The push-verifying {@link AttemptDelivery} for sandboxed git modes (FR21 of
 * add-sandbox-core): confirms the attempt commit carried by the {@link AttemptCommitWorkspace}
 * is on the remote before an external check's poll loop starts. First the cheap read: {@code
 * ls-remote} the task branch and, when the remote tip is an object the factory clone already
 * has, answer from ancestry alone with no push. Otherwise the commit is (or may be) undelivered:
 * re-attempt the push — same refspec convention as {@link BestEffortPush}/{@link BranchPush}
 * ({@code origin branch:branch}, never {@code --force}) — once more after a first failure. A
 * commit that still cannot be delivered is {@link AttemptDelivery.Outcome.Undeliverable}: the
 * check resolves as CannotVerify (infrastructure failure), never as a poll-timeout quality
 * failure. No configured {@code origin} is likewise undeliverable — a purely local run has no
 * remote for a CI trigger to have fired on.
 *
 * <p>Implements FR21 of add-sandbox-core.
 */
public final class RemoteAttemptDelivery implements AttemptDelivery {

    private static final Logger log = LoggerFactory.getLogger(RemoteAttemptDelivery.class);
    private static final String REMOTE = "origin";

    private final GitProcessRunner runner;
    private final Path cloneRoot;
    private final String branch;

    /**
     * @param runner the git subprocess seam; never null
     * @param cloneRoot the factory clone the branch and its objects live in; never null
     * @param branch the task branch name whose tip carries the attempt commit; never null
     */
    public RemoteAttemptDelivery(GitProcessRunner runner, Path cloneRoot, String branch) {
        this.runner = runner;
        this.cloneRoot = cloneRoot;
        this.branch = branch;
    }

    @Override
    public Outcome ensureDelivered(Workspace workspace) {
        if (!(workspace instanceof AttemptCommitWorkspace attemptWorkspace)) {
            return new Outcome.Undeliverable(
                    "attempt-commit delivery requires the attempt-commit workspace",
                    "workspace is " + workspace.getClass().getName() + ", which carries no attempt commit");
        }
        String attempt = attemptWorkspace.attemptCommitSha();

        GitCommandResult originUrl = runner.run(cloneRoot, "remote", "get-url", REMOTE);
        if (originUrl.exitCode() != 0) {
            return new Outcome.Undeliverable(
                    "no remote to deliver the attempt commit to",
                    "no '" + REMOTE + "' remote is configured, but the external check expects CI runs of the pushed"
                            + " attempt commit " + attempt);
        }

        if (deliveredPerRemoteTip(attempt)) {
            return new Outcome.Delivered();
        }

        GitCommandResult push = push();
        if (push.exitCode() != 0) {
            log.warn(
                    "attempt-commit delivery push failed, re-attempting once: branch={}, stderr={}",
                    branch,
                    push.stderr().trim());
            push = push();
        }
        if (push.exitCode() != 0) {
            return new Outcome.Undeliverable(
                    "attempt commit could not be delivered to the remote",
                    "push of " + branch + " failed twice; attempt commit " + attempt + " is not confirmed on '" + REMOTE
                            + "': " + push.stderr().trim());
        }
        return new Outcome.Delivered();
    }

    /**
     * The cheap confirmation: the remote branch tip, when it is an object the factory clone
     * already has, proves delivery iff the attempt commit is its ancestor. An unreachable
     * remote, an absent remote branch, or a tip unknown locally all answer {@code false} —
     * the push path then settles it.
     */
    private boolean deliveredPerRemoteTip(String attempt) {
        GitCommandResult lsRemote = runner.run(cloneRoot, "ls-remote", REMOTE, "refs/heads/" + branch);
        if (lsRemote.exitCode() != 0 || lsRemote.stdout().isBlank()) {
            return false;
        }
        String remoteTip = lsRemote.stdout().strip().split("\\s+", 2)[0];
        GitCommandResult ancestry = runner.run(cloneRoot, "merge-base", "--is-ancestor", attempt, remoteTip);
        return ancestry.exitCode() == 0;
    }

    private GitCommandResult push() {
        return runner.run(cloneRoot, "push", REMOTE, branch + ":" + branch);
    }
}
