package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.adapter.environment.ChildEnvAllowlist;
import com.github.oinsio.gnomish.adapter.environment.HostTaskExecutionEnvironment;
import com.github.oinsio.gnomish.adapter.environment.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;

/**
 * The host-mode {@link CheckEnvironmentSource} (G4, D20: host isolation
 * mechanics unchanged): a fresh {@link HostTaskExecutionEnvironment}
 * materialized over the {@link DirectoryWorkspace} root per check, disposed
 * after — exactly the flow {@link ShellCommandCheckRunner} inlined before the
 * seam existed. The freshness knob is host-inapplicable: host mode has no box
 * to be fresh of, so {@code verify-in} is ignored here by design (FR13 applies
 * to sandboxed modes).
 *
 * <p>Implements FR2, FR4 of add-sandbox-core.
 */
record HostCheckEnvironmentSource(Clock clock, ChildEnvAllowlist childEnv) implements CheckEnvironmentSource {

    @Override
    public Acquired acquire(VerifyCheck.Command check, Workspace workspace) {
        if (!(workspace instanceof DirectoryWorkspace directoryWorkspace)) {
            throw new CheckEnvironmentUnavailableException("command check requires a DirectoryWorkspace, got "
                    + workspace.getClass().getName());
        }
        var environment = new HostTaskExecutionEnvironment(directoryWorkspace.root(), clock, childEnv);
        environment.materialize("workspace:" + directoryWorkspace.root().getFileName(), null);
        return new Acquired() {

            @Override
            public TaskExecutionEnvironment environment() {
                return environment;
            }

            @Override
            public void close() {
                environment.dispose();
            }
        };
    }
}
