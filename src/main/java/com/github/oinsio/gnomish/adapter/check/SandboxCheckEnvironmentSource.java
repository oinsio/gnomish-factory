package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.adapter.environment.ContainerEnvironments;
import com.github.oinsio.gnomish.adapter.environment.EnvironmentLease;
import com.github.oinsio.gnomish.adapter.environment.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.adapter.workspace.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.function.Supplier;

/**
 * The sandboxed {@link CheckEnvironmentSource} (FR13, the integration pass of
 * add-sandbox-core): {@code verify-in: same-box} checks (the default) run in
 * the round's leased environment — released as a no-op, the lease owns it;
 * {@code verify-in: fresh-box} checks run in a fresh environment materialized
 * (and self-checked, FR8) from the attempt commit carried by the {@link
 * AttemptCommitWorkspace}, proving branch self-sufficiency — disposed on
 * release. A failed fresh-box materialization (guard, runtime, self-check)
 * surfaces as {@link CheckEnvironmentUnavailableException} → {@code
 * CannotVerify}: an infrastructure failure, no stage attempt burned (NFR-R1).
 *
 * <p>Implements FR8, FR13, NFR-R1 of add-sandbox-core.
 */
public final class SandboxCheckEnvironmentSource implements CheckEnvironmentSource {

    private final EnvironmentLease lease;
    private final Supplier<TaskExecutionEnvironment> freshEnvironments;
    private final String branch;

    /**
     * @param lease the run's environment lease, serving same-box checks; never null
     * @param environments the per-task environment construction seam for fresh boxes; never null
     * @param branch the task branch fresh boxes materialize from; never blank
     */
    public SandboxCheckEnvironmentSource(EnvironmentLease lease, ContainerEnvironments environments, String branch) {
        this(lease, environments::verificationEnvironment, branch);
    }

    /**
     * Testing seam (package-private): the same source with the fresh-box environment supplied by
     * the caller, so a spec can drive the fresh-box lifecycle (materialize failure → dispose →
     * {@link CheckEnvironmentUnavailableException}) against a fake environment — {@link
     * ContainerEnvironments} is a final Docker-backed class that cannot be faked directly.
     * Production always goes through the public constructor.
     */
    SandboxCheckEnvironmentSource(
            EnvironmentLease lease, Supplier<TaskExecutionEnvironment> freshEnvironments, String branch) {
        this.lease = lease;
        this.freshEnvironments = freshEnvironments;
        this.branch = branch;
    }

    @Override
    public Acquired acquire(VerifyCheck.Command check, Workspace workspace) {
        return switch (check.verifyIn()) {
            case SAME_BOX -> leased();
            case FRESH_BOX -> freshBox(workspace);
        };
    }

    private Acquired leased() {
        TaskExecutionEnvironment environment = lease.current();
        return new Acquired() {

            @Override
            public TaskExecutionEnvironment environment() {
                return environment;
            }

            @Override
            public void close() {
                // The lease owns the round environment's lifecycle (FR12); nothing to release.
            }
        };
    }

    private Acquired freshBox(Workspace workspace) {
        if (!(workspace instanceof AttemptCommitWorkspace attemptWorkspace)) {
            throw new CheckEnvironmentUnavailableException(
                    "verify-in: fresh-box requires an attempt-commit workspace, got "
                            + workspace.getClass().getName());
        }
        TaskExecutionEnvironment fresh = freshEnvironments.get();
        try {
            fresh.materialize(branch, attemptWorkspace.attemptCommitSha());
        } catch (RuntimeException e) {
            fresh.dispose();
            throw new CheckEnvironmentUnavailableException("fresh-box environment could not be materialized: " + e, e);
        }
        return new Acquired() {

            @Override
            public TaskExecutionEnvironment environment() {
                return fresh;
            }

            @Override
            public void close() {
                fresh.dispose();
            }
        };
    }
}
