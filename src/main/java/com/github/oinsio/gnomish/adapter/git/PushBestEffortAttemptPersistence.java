package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.nio.file.Path;

/**
 * Decorates a sandboxed {@link AttemptPersistence} with the best-effort push
 * every round boundary owes the remote (git-task-persistence "Best-effort
 * push"): after the delegate durably lands the snapshot/state pair in the
 * factory clone (harvest included), the task branch is pushed factory-side
 * with factory credentials — never from inside an environment (FR5, push
 * safety rules). A failed push is one WARN inside {@link BranchPush} and work
 * continues; durability is the recorded branch state.
 *
 * <p>Implements FR5 of add-sandbox-core.
 */
// Not a record: this is a behavior-bearing decorator (delegates a persist call and then triggers a
// push side effect), not immutable data, kept as a plain final class for parity with its documented
// siblings BranchPush / GithubClaimLease.
@SuppressWarnings("ClassCanBeRecord")
public final class PushBestEffortAttemptPersistence implements AttemptPersistence {

    private final AttemptPersistence delegate;
    private final BranchPush push;
    private final Path cloneDir;
    private final String branch;

    /**
     * @param delegate the strict persistence the round commits through; never null
     * @param push the factory-side push seam; never null
     * @param cloneDir the factory clone the push runs from; never null
     * @param branch the task branch to push; never blank
     */
    public PushBestEffortAttemptPersistence(
            AttemptPersistence delegate, BranchPush push, Path cloneDir, String branch) {
        this.delegate = delegate;
        this.push = push;
        this.cloneDir = cloneDir;
        this.branch = branch;
    }

    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        delegate.persist(taskId, state, trace);
        push.pushBestEffort(cloneDir, branch);
    }
}
