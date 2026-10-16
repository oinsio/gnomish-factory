package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchLocationRefusedException;
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Recovers a delivered task's pre-cleanup {@code .gnomish-task/} state from branch history, for the
 * reconcile-on-resume path (FR10, D10, NFR-C1 of add-claim-heartbeat). On {@code Completed}, {@link
 * GitTaskRepository#recordOutcome} writes the {@code Completed} {@code task.json} in one commit and
 * then a follow-up cleanup commit removes {@code .gnomish-task/} from the tip ({@link
 * GitObjectsTerminalCommits#cleanUp}; FR15 of add-git-workflow) — so a delivered branch whose
 * tracker finish never landed carries no live state at its tip, only in history (M4 of
 * add-git-workflow). This reader reads the delivered {@link
 * com.github.oinsio.gnomish.domain.engine.TaskContext} and final {@link TaskState} from the
 * {@code Completed} commit, so the reconcile can post the deferred finish faithfully from the
 * branch's own recorded outcome rather than fabricating one.
 *
 * <p>Branch lookup is delegated verbatim to {@link TaskBranchLocator} (local -> remote-tracking ->
 * narrow fetch -> not found), exactly as {@link BranchStateReader} does: the lookup's touchpoint
 * fetch carries the task's factory register with the branch (design D13 of tighten-spec-contract).
 *
 * <p>Which revision the files are read at is the register's, never the tip's (FR6, design D5 of
 * fix-envelope-medium; design D13 of tighten-spec-contract): the registered commit itself when it
 * still carries the envelope — a {@code CompletedUncleaned} branch — and otherwise, when the
 * registered commit is the cleanup commit, the {@code Completed} commit the cleanup was written on.
 * A {@code Completed} envelope the gnome committed above the register, or a cleanup subject it
 * wrote, is never read. See {@link #deliveredRevision}.
 *
 * <p>Every revision-scoped read below passes {@link GitReadGate#answered}, so this reader can tell
 * "the delivered commit does not carry the files" from "the question was not answered": an
 * interrupted or timed-out {@code git show} surfaces as unavailability instead of as a missing
 * state file.
 *
 * <p>Implements FR10 of add-claim-heartbeat; FR6 of fix-envelope-medium; FR13, NFR-S2 of
 * tighten-spec-contract.
 */
public final class DeliveredBranchReader {

    private static final String TASK_JSON_PATH = EnvelopePaths.TASK_JSON_PATH;
    private static final String STATE_JSON_PATH = EnvelopePaths.STATE_JSON_PATH;

    private final GitRunner runner;
    private final TaskBranchLocator locator;
    private final FactoryRegisterRead register;

    /**
     * @param runner the git subprocess seam; never null
     * @param retry the infrastructure budget the branch lookup is re-attempted under, the
     *     composition root's own (FR18 of supervise-daemon-loops-and-embed-dashboard); never null
     */
    public DeliveredBranchReader(GitRunner runner, GitInfrastructureRetry retry) {
        this.runner = runner;
        this.locator = new TaskBranchLocator(runner, retry);
        this.register = new FactoryRegisterRead(runner);
    }

    /**
     * Reads the delivered {@code task.json}/{@code state.json} of the task branch for {@code taskId}
     * from the {@code Completed} commit its history records.
     *
     * <p>Implements FR10 of add-claim-heartbeat.
     *
     * @param cloneDir the working directory of the existing git clone (the {@code --dir} target)
     * @param taskId the tracker's original taskId
     * @return the recovered delivered context and final state
     * @throws GitTaskRepositoryException if no branch exists anywhere for {@code taskId}
     * @throws BranchLocationUnavailableException if origin could not be asked whether the branch
     *     exists — unavailability is never reported as absence (FR6 of
     *     harden-task-branch-contract)
     * @throws com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException if a {@code git
     *     show} of the delivered commit did not run to its own exit, so its capture is not a fact
     *     about that revision
     * @throws BranchStateFileMissingException if no delivered commit can be located, or the
     *     located one does not carry the state files
     */
    public DeliveredBranchState read(Path cloneDir, String taskId) {
        RegisteredCommit delivered = resolveDeliveredRef(cloneDir, taskId);
        TaskRecord content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(show(cloneDir, delivered, TASK_JSON_PATH)));
        TaskState finalState =
                StateJsonMapper.fromDto(StateJsonMapper.readDto(show(cloneDir, delivered, STATE_JSON_PATH)));
        return new DeliveredBranchState(content.context(), finalState);
    }

    private RegisteredCommit resolveDeliveredRef(Path cloneDir, String taskId) {
        BranchLocation location = locator.locate(cloneDir, taskId);
        String tip =
                switch (location) {
                    case BranchLocation.Local local -> local.ref();
                    case BranchLocation.RemoteTracking tracking -> tracking.ref();
                    case BranchLocation.Unavailable(UntrustedText reason) ->
                        throw new BranchLocationUnavailableException(taskId, reason);
                    case BranchLocation.Refused(UntrustedText report) ->
                        throw new BranchLocationRefusedException(taskId, report);
                    case BranchLocation.NotFound ignored ->
                        throw new GitTaskRepositoryException(
                                taskId,
                                TaskLifecycleEvent.COMPLETED,
                                "locating delivered branch",
                                UntrustedText.factory("no branch found to reconcile a deferred finish from"));
                };
        RegisteredCommit registered = register.registered(cloneDir, taskId)
                .orElseThrow(() -> new BranchStateFileMissingException(
                        tip, TASK_JSON_PATH, UntrustedText.factory("no factory register names a commit of this task")));
        return deliveredRevision(cloneDir, registered);
    }

    /**
     * The commit whose tree carries the delivered envelope (design D5 of fix-envelope-medium,
     * design D13 of tighten-spec-contract): the registered commit itself when it still carries
     * {@code task.json} — a {@code CompletedUncleaned} branch, whose {@code Completed} envelope has
     * not been removed yet — and otherwise, when the registered commit is the cleanup commit, the
     * commit it was written on, which is the {@code Completed} commit by construction. That is the
     * same commit the branch classifier calls a delivery, so the reader and the classifier cannot
     * disagree about which one it is.
     *
     * @throws BranchStateFileMissingException when neither holds — a registered commit with no
     *     envelope that is not the cleanup commit was never delivered, so there is no state to
     *     recover
     */
    private RegisteredCommit deliveredRevision(Path cloneDir, RegisteredCommit registered) {
        GitShowTip reader = new GitShowTip(runner, cloneDir, registered);
        if (reader.readAtRegister(TASK_JSON_PATH).isPresent()) {
            return registered;
        }
        Optional<RegisteredCommit> completed =
                reader.isCleanupCommit() ? register.completedBefore(cloneDir, registered) : Optional.empty();
        return completed.orElseThrow(() -> new BranchStateFileMissingException(
                registered.id(),
                TASK_JSON_PATH,
                UntrustedText.factory("the registered commit carries no envelope and is not the cleanup commit")));
    }

    private UntrustedText show(Path cloneDir, RegisteredCommit commit, String filePath) {
        String ref = commit.id();
        // GitReadGate.answered: an interrupted or timed-out `git show` would otherwise read as
        // "the delivered commit carries no state files", turning unavailability into a missing-file
        // verdict the reconcile cannot tell from a genuinely stateless commit.
        GitCommandResult result = GitReadGate.answered(ref, "show", runner.run(cloneDir, "show", ref + ":" + filePath));
        if (result.exitCode() != 0) {
            throw new BranchStateFileMissingException(ref, filePath, result.stderr());
        }
        return result.stdout();
    }
}
