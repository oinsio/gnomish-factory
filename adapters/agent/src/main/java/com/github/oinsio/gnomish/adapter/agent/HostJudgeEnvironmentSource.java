package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.app.port.agent.JudgeEnvironmentSource;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment;

/**
 * The host-mode {@link JudgeEnvironmentSource} (G4: host isolation mechanics unchanged): a {@link
 * HostTaskExecutionEnvironment} over the graded {@link DirectoryWorkspace}'s root — the stage
 * workspace, as today — mirroring {@link HostRoundEnvironmentSource}'s own host default. Sandboxed
 * mode wires {@link FreshJudgeEnvironments} instead, so a vote never grades inside the
 * gnome-touched round environment.
 *
 * <p>Was a {@code static host(...)} factory on the {@link JudgeEnvironmentSource} port itself;
 * lifted into the adapter layer by task 4.4 (D12(b) of split-into-modules) because a port
 * interface must not construct the adapter that implements it.
 *
 * <p>Implements FR15, D9 of add-sandbox-core.
 *
 * @param clock the exec start-instant source; never null
 * @param childEnv the run's layered child-env allowlist (D6, FR9); never null
 */
public record HostJudgeEnvironmentSource(Clock clock, ChildEnvAllowlist childEnv) implements JudgeEnvironmentSource {

    @Override
    public TaskExecutionEnvironment environmentFor(Workspace workspace) {
        return new HostTaskExecutionEnvironment(((DirectoryWorkspace) workspace).root(), clock, childEnv);
    }
}
