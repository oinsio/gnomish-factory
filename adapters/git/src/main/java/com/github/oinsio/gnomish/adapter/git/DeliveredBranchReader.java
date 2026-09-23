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

/**
 * Recovers a delivered task's pre-cleanup {@code .gnomish-task/} state from branch history, for the
 * reconcile-on-resume path (FR10, D10, NFR-C1 of add-claim-heartbeat). On {@code Completed}, {@link
 * GitTaskRepository#recordOutcome} writes the {@code Completed} {@code task.json} in one commit and
 * then adds a follow-up cleanup commit that {@code git rm}s {@code .gnomish-task/} from the tip
 * (FR15 of add-git-workflow) — so a delivered branch whose tracker finish never landed carries no
 * live state at its tip, only in history (M4 of add-git-workflow). This reader reads the delivered
 * {@link com.github.oinsio.gnomish.domain.engine.TaskContext} and final {@link TaskState} from the
 * {@code Completed} commit, so the reconcile can post the deferred finish faithfully from the
 * branch's own recorded outcome rather than fabricating one.
 *
 * <p>Branch lookup is delegated verbatim to {@link TaskBranchLocator} (local -> remote-tracking ->
 * narrow fetch -> not found), exactly as {@link BranchStateReader} does for the tip; the only
 * difference is which revision of the located branch the files are read at.
 *
 * <p>Which revision that is, is resolved rather than assumed (FR6, design D5 of
 * fix-envelope-medium): the tip when the tip still carries the envelope, and otherwise the parent
 * of the cleanup commit {@link GitShowTip#cleanupCommit()} locates in the tip's history. See
 * {@link #deliveredRevision}.
 *
 * <p>Every revision-scoped read below passes {@link GitReadGate#answered}, so this reader can tell
 * "the delivered commit does not carry the files" from "the question was not answered": an
 * interrupted or timed-out {@code git show} surfaces as unavailability instead of as a missing
 * state file.
 *
 * <p>Implements FR10 of add-claim-heartbeat; FR6 of fix-envelope-medium.
 */
public final class DeliveredBranchReader {

    private static final String TASK_JSON_PATH = EnvelopePaths.TASK_JSON_PATH;
    private static final String STATE_JSON_PATH = EnvelopePaths.STATE_JSON_PATH;

    private final GitProcessRunner runner;
    private final TaskBranchLocator locator;

    public DeliveredBranchReader(GitProcessRunner runner) {
        this.runner = runner;
        this.locator = new TaskBranchLocator(runner);
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
        String delivered = resolveDeliveredRef(cloneDir, taskId);
        TaskRecord content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(show(cloneDir, delivered, TASK_JSON_PATH)));
        TaskState finalState =
                StateJsonMapper.fromDto(StateJsonMapper.readDto(show(cloneDir, delivered, STATE_JSON_PATH)));
        return new DeliveredBranchState(content.context(), finalState);
    }

    private String resolveDeliveredRef(Path cloneDir, String taskId) {
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
        return deliveredRevision(cloneDir, tip);
    }

    /**
     * The revision whose tree carries the delivered envelope (design D5 of fix-envelope-medium):
     * the tip itself when it still carries {@code task.json} — a {@code CompletedUncleaned} branch,
     * whose {@code Completed} envelope has not been removed yet — and otherwise the parent of the
     * cleanup commit located in the tip's history, which is the {@code Completed} commit by
     * construction. That is the same commit the branch classifier calls a delivery, so the reader
     * and the classifier cannot disagree about which one it is; reading {@code tip^} by assumption
     * did, on every branch that gained a commit after cleanup.
     *
     * @throws BranchStateFileMissingException when neither holds — a tip with no envelope and no
     *     cleanup commit in its history was never delivered, so there is no state to recover
     */
    private String deliveredRevision(Path cloneDir, String tip) {
        GitShowTip tipReader = new GitShowTip(runner, cloneDir, tip);
        if (tipReader.readAtTip(TASK_JSON_PATH).isPresent()) {
            return tip;
        }
        return tipReader
                .cleanupCommit()
                .map(cleanup -> cleanup + "^")
                .orElseThrow(() -> new BranchStateFileMissingException(
                        tip,
                        TASK_JSON_PATH,
                        UntrustedText.factory("the tip carries no envelope and its history holds no cleanup commit")));
    }

    private UntrustedText show(Path cloneDir, String ref, String filePath) {
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
