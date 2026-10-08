package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.app.port.agent.JudgeEnvironmentSource;
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.sandbox.LiveBox;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.util.function.Supplier;

/**
 * The sandboxed {@link JudgeEnvironmentSource} (FR15, D9 of add-sandbox-core): every attempt's
 * judge votes run in a fresh environment materialized from that attempt's commit — built from
 * the image and the branch state alone, so a gnome-poisoned round box (PATH shims, planted
 * binaries outside the working copy) cannot grade itself. Votes of the same attempt share the
 * fresh environment: judges are read-only, and the commit pin makes every vote of the attempt
 * see the identical tree. A new attempt commit disposes the previous judge box and materializes
 * a new one pinned at the new commit; {@link #disposeCurrent()} tears the last one down when
 * the stage's verification ends.
 *
 * <p><b>Lock scope:</b> this class holds no lock. The one live judge box, its lock and its build
 * are {@link LiveBox}'s (D13 of make-checkpoint-gate-durable), keyed by the attempt commit: votes
 * of the same attempt wait on that build's future, not on a monitor, so a second box for one
 * attempt commit is never built, and {@link #disposeCurrent()} waits for a build in flight before
 * it tears the box down.
 *
 * <p>Landed additively (integration-pass precedent of task 4.8): the app wiring that binds this
 * source into the container-mode assembly follows with the sandbox integration pass; the
 * component and its contract are complete here.
 *
 * <p>Implements FR15, NFR-S2, D9 of add-sandbox-core; FR21 of make-checkpoint-gate-durable.
 */
public final class FreshJudgeEnvironments implements JudgeEnvironmentSource {

    private final LiveBox<String> box;

    /**
     * @param environmentFactory creates a fresh, unmaterialized environment per attempt (the
     *     bound adapter's construction seam); never null
     * @param branch the task branch the attempt commits live on; never null
     */
    public FreshJudgeEnvironments(Supplier<TaskExecutionEnvironment> environmentFactory, String branch) {
        this.box = new LiveBox<>(environmentFactory, (fresh, sha) -> fresh.materialize(branch, sha));
    }

    @Override
    public TaskExecutionEnvironment environmentFor(Workspace workspace) {
        return box.environmentFor(((RecordedAttemptCommitWorkspace) workspace).attemptCommitSha());
    }

    /** Disposes the current judge box, if any; idempotent. */
    public void disposeCurrent() {
        box.dispose();
    }
}
