package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.app.port.agent.RoundDecisionRequest;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment;
import com.github.oinsio.gnomish.subprocess.ProcessLaunch;
import java.nio.file.Path;
import java.time.InstantSource;
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
 * <p>This source mints no round token: it has no git and needs none, because undecorated it serves
 * the in-place run only, whose workspace need not be a repository ({@code cli/manual-run}, "In-place
 * mode needs no git"), so its {@link #identity()} is {@link RoundIdentity#inPlace()}. A git-mode
 * host run decorates it with {@code com.github.oinsio.gnomish.adapter.git.MidRoundPushRounds}, the
 * one piece of the host wiring that holds git, which mints the round token over the worktree tip
 * through {@code RoundToken.of} and names its own cell as the identity (FR21 of
 * define-executor-contract, design D13: the tip reader only the git-mode wiring passes).
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource}
 * (no shared classpath between {@code adapters/agent} and {@code adapters/git}): both implement
 * {@link RoundEnvironmentSource} for their execution mode and must open/close rounds with a
 * decision-file handle, a round listener, and close-round semantics consistent with FR4/FR21; and
 * in a branch mode both rounds open on a round token minted from the tip the round opens on through
 * {@code RoundToken.of} — for the host, by the git-mode decoration above, never by this source; and
 * both hand out an environment with a scratch area from the open to the close — here a host temp
 * directory materialized at the open and disposed at the close or discard, there the live box's
 * (task 7.1 of define-executor-contract: a program executor names its result files under it).
 *
 * <p>Implements FR2, FR4 of add-sandbox-core; FR21 of define-executor-contract.
 *
 * @param transport the temp-dir decision transport (a testing seam supplies a rooted one)
 * @param clock the exec start-instant source; never null
 * @param childEnv the run's layered child-env allowlist (D6, FR9); never null
 */
record HostRoundEnvironmentSource(DecisionFileTransport transport, InstantSource clock, ChildEnvAllowlist childEnv)
        implements RoundEnvironmentSource {

    @Override
    public Round openRound(StageExecutor.Request request) {
        var workspace = (DirectoryWorkspace) request.workspace();
        var environment = new HostTaskExecutionEnvironment(workspace.root(), clock, childEnv, ProcessLaunch.grouped());
        // A round's environment has a scratch area, as a box round's has: a program executor names
        // its result files under it (FR5 of define-executor-contract). The host one leaves with the
        // round, at its close or its discard.
        environment.materialize("round:" + request.context().taskId(), null);
        DecisionFileTransport.Handle handle = transport.open();
        return new HostRound(environment, handle);
    }

    /** The in-place identity: undecorated, this source serves the git-less in-place run. */
    @Override
    public RoundIdentity identity() {
        return RoundIdentity.inPlace();
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
            // Host rounds close as the single round commit in persistence (FR21); only the round's
            // scratch area leaves here.
            environment.dispose();
        }

        @Override
        public Optional<RoundDecisionRequest> readDecision() {
            return handle.readAndClose();
        }

        @Override
        public void discard() {
            environment.dispose();
            handle.discard();
        }
    }
}
