package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.nio.file.Path;

/**
 * Decorates a sandboxed {@link AttemptPersistence} with the best-effort closing push every round
 * boundary owes the remote (git-task-persistence "Best-effort push"): after the delegate durably
 * lands the snapshot/state pair in the factory clone — the admission moved the branch and the
 * register to the state commit in one ref transaction — the task branch and the register are pushed
 * factory-side, in one atomic push, with factory credentials — never from inside an environment (FR5, push safety rules;
 * design D13 of tighten-spec-contract). A failed push is one WARN inside {@link
 * BestEffortClosingPush} and work continues; durability is the recorded branch state.
 *
 * <p>Implements FR5 of add-sandbox-core; FR13 of tighten-spec-contract.
 */
// Not a record: this is a behavior-bearing decorator (delegates a persist call and then triggers a
// push side effect), not immutable data, kept as a plain final class for parity with its documented
// sibling PushBestEffortTaskRepository.
@SuppressWarnings("ClassCanBeRecord")
public final class PushBestEffortAttemptPersistence implements AttemptPersistence {

    private final AttemptPersistence delegate;
    private final BestEffortClosingPush push;
    private final Path cloneDir;

    /**
     * @param delegate the strict persistence the round commits through; never null
     * @param push the factory-side closing push; never null
     * @param cloneDir the factory clone the push runs from; never null
     */
    public PushBestEffortAttemptPersistence(AttemptPersistence delegate, BestEffortClosingPush push, Path cloneDir) {
        this.delegate = delegate;
        this.push = push;
        this.cloneDir = cloneDir;
    }

    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        delegate.persist(taskId, state, trace);
        push.pushBestEffort(cloneDir, taskId);
    }
}
