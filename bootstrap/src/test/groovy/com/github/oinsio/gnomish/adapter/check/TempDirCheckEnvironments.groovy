package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import java.nio.file.Path

/**
 * A {@link CheckEnvironmentSource} that serves every command check from one fixed directory,
 * ignoring the {@link Workspace} it is handed.
 *
 * <p>It exists for a real modelling constraint, not for convenience. A verify chain has exactly one
 * workspace, and the two default consumers want different subtypes of it: {@code
 * HostCheckEnvironmentSource} requires a {@code DirectoryWorkspace}, while the github check client
 * requires an {@code RecordedAttemptCommitWorkspace} to know which commit to ask the platform about. In
 * production that tension is resolved by {@code SandboxCheckEnvironmentSource}, which serves the
 * command check from the round's container lease while the workspace stays an {@code
 * RecordedAttemptCommitWorkspace} — so a mixed chain really is supported, but only over Docker. This stands
 * in for the lease so the acceptance chain stays in-process.
 */
class TempDirCheckEnvironments implements CheckEnvironmentSource {

    private final Path directory
    private final Clock clock

    TempDirCheckEnvironments(Path directory, Clock clock) {
        this.directory = directory
        this.clock = clock
    }

    @Override
    Acquired acquire(VerifyCheck.Command check, Workspace workspace) {
        def environment = new HostTaskExecutionEnvironment(directory, clock, ChildEnvAllowlist.none())
        environment.materialize('acceptance', null)
        new Acquired() {

                    @Override
                    TaskExecutionEnvironment environment() {
                        environment
                    }

                    @Override
                    void close() {
                        environment.dispose()
                    }
                }
    }
}
