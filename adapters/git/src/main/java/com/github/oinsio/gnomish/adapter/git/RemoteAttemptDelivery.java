package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The push-verifying {@link AttemptDelivery} for sandboxed git modes (FR21 of
 * add-sandbox-core): confirms the attempt commit carried by the {@link RecordedAttemptCommitWorkspace}
 * is on the remote before an external check's poll loop starts. First the cheap read: {@code
 * ls-remote} the task branch through the shared {@link RemoteBranchTip} and, when the remote tip
 * is an object the factory clone already has, answer from ancestry alone with no push. Otherwise
 * the commit is (or may be) undelivered: re-attempt the push — the shared {@link RefspecPush},
 * same as every other push point ({@code origin branch:branch}, never {@code --force}) — once
 * more after a first failure. A
 * commit that still cannot be delivered is {@link AttemptDelivery.Outcome.Undeliverable}: the
 * check resolves as CannotVerify (infrastructure failure), never as a poll-timeout quality
 * failure. No configured {@code origin} is likewise undeliverable — a purely local run has no
 * remote for a CI trigger to have fired on.
 *
 * <p>A push that did not run to its own exit spends no re-attempt (FR7 of
 * bound-subprocess-commands): a killed or interrupted push established no remote outcome, so
 * repeating it would only spend a second full deadline on a remote already proven unresponsive.
 * Both cases are {@link Outcome.Undeliverable} — which the check resolves as CannotVerify,
 * infrastructure rather than quality — with a reason that says the outcome is unknown rather than
 * negative.
 *
 * <p>Implements FR21 of add-sandbox-core; FR7 of bound-subprocess-commands.
 */
public final class RemoteAttemptDelivery implements AttemptDelivery {

    private static final Logger log = LoggerFactory.getLogger(RemoteAttemptDelivery.class);

    private final OriginRemote origin;
    private final RemoteBranchTip remoteTip;
    private final RefspecPush refspecPush;
    private final Path cloneRoot;
    private final String branch;

    /**
     * @param runner the git subprocess seam; never null
     * @param cloneRoot the factory clone the branch and its objects live in; never null
     * @param branch the task branch name whose tip carries the attempt commit; never null
     */
    public RemoteAttemptDelivery(GitProcessRunner runner, Path cloneRoot, String branch) {
        this.origin = new OriginRemote(runner);
        this.remoteTip = new RemoteBranchTip(runner);
        this.refspecPush = new RefspecPush(runner);
        this.cloneRoot = cloneRoot;
        this.branch = branch;
    }

    @Override
    public Outcome ensureDelivered(Workspace workspace) {
        if (!(workspace instanceof RecordedAttemptCommitWorkspace attemptWorkspace)) {
            return new Outcome.Undeliverable(
                    "attempt-commit delivery requires the attempt-commit workspace",
                    "workspace is " + workspace.getClass().getName() + ", which carries no attempt commit");
        }
        String attempt = attemptWorkspace.attemptCommitSha();

        if (!origin.isConfigured(cloneRoot)) {
            return new Outcome.Undeliverable(
                    "no remote to deliver the attempt commit to",
                    "no '" + OriginRemote.NAME
                            + "' remote is configured, but the external check expects CI runs of the pushed"
                            + " attempt commit " + attempt);
        }

        if (remoteTip.carries(cloneRoot, branch, attempt)) {
            return new Outcome.Delivered();
        }

        GitCommandResult push = push();
        if (push.termination() == Termination.EXITED && push.exitCode() != 0) {
            log.warn(
                    "attempt-commit delivery push failed, re-attempting once: branch={}, stderr={}",
                    branch,
                    push.stderr().trim());
            push = push();
        }
        if (push.termination() != Termination.EXITED) {
            log.warn(
                    "attempt-commit delivery push {}: branch={}",
                    push.termination() == Termination.TIMED_OUT ? "timed out" : "was interrupted",
                    branch);
            return new Outcome.Undeliverable(
                    "attempt-commit delivery could not be verified",
                    "push of " + branch
                            + (push.termination() == Termination.TIMED_OUT
                                    ? " was cut off on its deadline"
                                    : " was interrupted before it finished")
                            + ", so whether attempt commit " + attempt + " reached '" + OriginRemote.NAME
                            + "' is unknown");
        }
        if (push.exitCode() != 0) {
            return new Outcome.Undeliverable(
                    "attempt commit could not be delivered to the remote",
                    "push of " + branch + " failed twice; attempt commit " + attempt + " is not confirmed on '"
                            + OriginRemote.NAME + "': " + push.stderr().trim());
        }
        return new Outcome.Delivered();
    }

    private GitCommandResult push() {
        return refspecPush.push(cloneRoot, branch);
    }
}
