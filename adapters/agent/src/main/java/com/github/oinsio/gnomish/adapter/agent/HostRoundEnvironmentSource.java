package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The host-mode {@link RoundEnvironmentSource} (G4, D20: host isolation
 * mechanics unchanged): a fresh {@link HostTaskExecutionEnvironment} over the
 * {@link DirectoryWorkspace} root per round, with the decision file in a
 * factory-private temp directory ({@link DecisionFileTransport}) — exactly the
 * flow {@code CliStageExecutor} inlined before the seam existed. Rounds close
 * with no snapshot: host mode keeps the single round commit (FR21).
 *
 * <p>Implements FR2, FR4 of add-sandbox-core.
 *
 * @param transport the temp-dir decision transport (a testing seam supplies a rooted one)
 * @param clock the exec start-instant source; never null
 * @param childEnv the run's layered child-env allowlist (D6, FR9); never null
 */
record HostRoundEnvironmentSource(DecisionFileTransport transport, Clock clock, ChildEnvAllowlist childEnv)
        implements RoundEnvironmentSource {

    @Override
    public Round openRound(StageExecutor.Request request) {
        var workspace = (DirectoryWorkspace) request.workspace();
        var environment = new HostTaskExecutionEnvironment(workspace.root(), clock, childEnv);
        DecisionFileTransport.Handle handle = transport.open();
        return new HostRound(environment, handle);
    }

    private record HostRound(TaskExecutionEnvironment environment, DecisionFileTransport.Handle handle)
            implements Round {

        @Override
        public Path decisionFilePath() {
            return handle.decisionFilePath();
        }

        @Override
        public Map<String, String> decisionEnvFragment() {
            return handle.envFragment();
        }

        @Override
        public void closeRound() {
            // Host rounds close as the single round commit in persistence (FR21); nothing here.
        }

        @Override
        public Optional<String> readDecision() {
            return handle.readAndClose();
        }

        @Override
        public void discard() {
            handle.discard();
        }
    }
}
