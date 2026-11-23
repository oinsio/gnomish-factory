package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.app.describe.ProgramChannel;
import com.github.oinsio.gnomish.app.port.agent.DecisionRequestRead;
import com.github.oinsio.gnomish.app.port.agent.RoundClose;
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
 * {@link DirectoryWorkspace} root per round, with the round's answer file in
 * that environment's factory-private scratch area ({@link ProgramChannel#freshPath},
 * named to the agent in {@link ProgramChannel#ANSWER_FILE_VARIABLE}), outside the
 * workspace and gone with the round's scratch area (FR9 of
 * extract-agent-round-runner, design D3: the answer file has no transport of its
 * own). Rounds close with no snapshot: host mode keeps the single round commit
 * (FR21). The close reads the answer file through the one tolerant {@link
 * DecisionRequestRead} before the scratch area leaves, and {@code readDecision}
 * hands that request on.
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
 * {@link RoundEnvironmentSource} for their execution mode and must open/close rounds with an
 * answer file named in {@code GNOMISH_ANSWER_FILE}, a round listener, and close-round semantics consistent with FR4/FR21; and
 * in a branch mode both rounds open on a round token minted from the tip the round opens on through
 * {@code RoundToken.of} — for the host, by the git-mode decoration above, never by this source; and
 * both hand out an environment with a scratch area from the open to the close — here a host temp
 * directory materialized at the open and disposed at the close or discard, there the live box's
 * (task 7.1 of define-executor-contract: a program executor names its result files under it).
 *
 * <p>Implements FR2, FR4 of add-sandbox-core; FR21 of define-executor-contract; FR9 of
 * extract-agent-round-runner.
 *
 * @param clock the exec start-instant source; never null
 * @param childEnv the run's layered child-env allowlist (D6, FR9); never null
 */
public record HostRoundEnvironmentSource(InstantSource clock, ChildEnvAllowlist childEnv)
        implements RoundEnvironmentSource {

    /** The executor word the host round's answer file is named after in its scratch area. */
    private static final String ANSWER_NAME = "agent-cli";

    @Override
    public Round openRound(StageExecutor.Request request) {
        var workspace = (DirectoryWorkspace) request.workspace();
        var environment = new HostTaskExecutionEnvironment(workspace.root(), clock, childEnv, ProcessLaunch.grouped());
        // A round's environment has a scratch area, as a box round's has: a program executor names
        // its result files under it (FR5 of define-executor-contract). The host one leaves with the
        // round, at its close or its discard.
        environment.materialize("round:" + request.context().taskId(), null);
        return new HostRound(environment, ProgramChannel.freshPath(environment, ANSWER_NAME, "answer"));
    }

    /** The in-place identity: undecorated, this source serves the git-less in-place run. */
    @Override
    public RoundIdentity identity() {
        return RoundIdentity.inPlace();
    }

    /**
     * One host round. The request is read at the close, while the scratch area still holds the
     * file, and kept for {@link #readDecision()}: the round runs on one thread from its open to its
     * read, so the field is never shared.
     */
    private static final class HostRound implements Round {

        private final TaskExecutionEnvironment environment;
        private final String answerFile;
        private Optional<RoundDecisionRequest> request = Optional.empty();

        private HostRound(TaskExecutionEnvironment environment, String answerFile) {
            this.environment = environment;
            this.answerFile = answerFile;
        }

        @Override
        public TaskExecutionEnvironment environment() {
            return environment;
        }

        @Override
        public Path answerFilePath() {
            return Path.of(answerFile);
        }

        @Override
        public Map<String, String> answerEnvFragment() {
            return Map.of(ProgramChannel.ANSWER_FILE_VARIABLE, answerFile);
        }

        @Override
        public void closeRound(RoundClose close) {
            // Host rounds close in persistence (FR21), and a git-mode host round that completed is
            // snapshotted by the stage chain's decoration, which acts on the result itself (design
            // D22 of migrate-verify-to-executor-contract): the close names nothing here. The answer
            // file is read while it still exists; then the round's scratch area leaves.
            request = DecisionRequestRead.read(limit -> environment.readFile(answerFile, limit));
            environment.dispose();
        }

        @Override
        public Optional<RoundDecisionRequest> readDecision() {
            return request;
        }

        @Override
        public void discard() {
            environment.dispose();
        }
    }
}
