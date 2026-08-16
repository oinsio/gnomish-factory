package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;

/**
 * Recovers a delivered task's pre-cleanup {@code .gnomish-task/} state from branch history, for the
 * reconcile-on-resume path (FR10, D10, NFR-C1 of add-claim-heartbeat). On {@code Completed}, {@link
 * GitTaskRepository#recordOutcome} writes the {@code Completed} {@code task.json} in one commit and
 * then adds a follow-up cleanup commit that {@code git rm}s {@code .gnomish-task/} from the tip
 * (FR15 of add-git-workflow) — so a delivered branch whose tracker finish never landed carries no
 * live state at its tip, only in history (M4 of add-git-workflow). This reader reads the delivered
 * {@link com.github.oinsio.gnomish.domain.engine.TaskContext} and final {@link TaskState} from the
 * cleanup commit's parent (the {@code Completed} commit), so the reconcile can post the deferred
 * finish faithfully from the branch's own recorded outcome rather than fabricating one.
 *
 * <p>Branch lookup is delegated verbatim to {@link TaskBranchLocator} (local -> remote-tracking ->
 * narrow fetch -> not found), exactly as {@link BranchStateReader} does for the tip; the only
 * difference is the {@code ^} suffix that selects the parent of the located tip — the delivered
 * commit — since the tip itself is the cleanup commit that no longer carries the files.
 *
 * <p>Reading exactly the tip's parent is the minimal recovery that makes reconcile pass today: on
 * a delivered-but-unfinished branch the cleanup commit is always the tip (the finish is a
 * tracker-only write that adds no branch commit), so its parent is the {@code Completed} commit.
 * Hardening this against a branch that gained commits after cleanup — or retaining the state at the
 * tip until the finish write confirms — is task 6.5's durability concern, noted on {@code
 * com.github.oinsio.gnomish.app.TakeReconcile}.
 *
 * <p>Implements FR10 of add-claim-heartbeat.
 */
public final class DeliveredBranchReader {

    private static final String TASK_JSON_PATH = ".gnomish-task/task.json";
    private static final String STATE_JSON_PATH = ".gnomish-task/state.json";

    private final GitProcessRunner runner;
    private final TaskBranchLocator locator;

    public DeliveredBranchReader(GitProcessRunner runner) {
        this.runner = runner;
        this.locator = new TaskBranchLocator(runner);
    }

    /**
     * Reads the delivered {@code task.json}/{@code state.json} of the task branch for {@code taskId}
     * from the commit preceding its {@code Completed} cleanup commit.
     *
     * <p>Implements FR10 of add-claim-heartbeat.
     *
     * @param cloneDir the working directory of the existing git clone (the {@code --dir} target)
     * @param taskId the tracker's original taskId
     * @return the recovered delivered context and final state
     * @throws GitTaskRepositoryException if no branch exists anywhere for {@code taskId}
     * @throws BranchStateFileMissingException if the parent commit does not carry the state files
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
                    case BranchLocation.NotFound ignored ->
                        throw new GitTaskRepositoryException(
                                taskId,
                                TaskLifecycleEvent.COMPLETED,
                                "locating delivered branch",
                                "no branch found to reconcile a deferred finish from");
                };
        return tip + "^";
    }

    private String show(Path cloneDir, String ref, String filePath) {
        GitCommandResult result = runner.run(cloneDir, "show", ref + ":" + filePath);
        if (result.exitCode() != 0) {
            throw new BranchStateFileMissingException(ref, filePath, result.stderr());
        }
        return result.stdout();
    }
}
