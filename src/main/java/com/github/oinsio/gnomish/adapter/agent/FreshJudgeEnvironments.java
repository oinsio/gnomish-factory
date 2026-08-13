package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.adapter.environment.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.adapter.workspace.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

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
 * <p>Landed additively (integration-pass precedent of task 4.8): the app wiring that binds this
 * source into the container-mode assembly follows with the sandbox integration pass; the
 * component and its contract are complete here.
 *
 * <p>Implements FR15, NFR-S2, D9 of add-sandbox-core.
 */
public final class FreshJudgeEnvironments implements JudgeEnvironmentSource {

    private final Supplier<TaskExecutionEnvironment> environmentFactory;
    private final String branch;

    private @Nullable TaskExecutionEnvironment current;
    private @Nullable String currentSha;

    /**
     * @param environmentFactory creates a fresh, unmaterialized environment per attempt (the
     *     bound adapter's construction seam); never null
     * @param branch the task branch the attempt commits live on; never null
     */
    public FreshJudgeEnvironments(Supplier<TaskExecutionEnvironment> environmentFactory, String branch) {
        this.environmentFactory = environmentFactory;
        this.branch = branch;
    }

    @Override
    public synchronized TaskExecutionEnvironment environmentFor(Workspace workspace) {
        String sha = ((AttemptCommitWorkspace) workspace).attemptCommitSha();
        if (current != null && sha.equals(currentSha)) {
            return current;
        }
        disposeCurrent();
        TaskExecutionEnvironment fresh = environmentFactory.get();
        fresh.materialize(branch, sha);
        current = fresh;
        currentSha = sha;
        return fresh;
    }

    /** Disposes the current judge box, if any; idempotent. */
    public synchronized void disposeCurrent() {
        if (current != null) {
            current.dispose();
            current = null;
            currentSha = null;
        }
    }
}
