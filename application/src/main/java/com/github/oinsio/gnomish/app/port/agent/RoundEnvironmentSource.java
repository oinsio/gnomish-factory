package com.github.oinsio.gnomish.app.port.agent;

import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Where a {@code CliStageExecutor} round gets its execution environment and
 * decision transport (the sandbox integration pass of add-sandbox-core): the
 * host default builds a {@code HostTaskExecutionEnvironment} over the {@code
 * DirectoryWorkspace} root with the temp-dir decision file ({@code
 * DecisionFileTransport}), today's behavior; the sandboxed source leases the
 * task's container environment per segment, moves the decision file into the
 * branch (FR23, D17), and closes each round with the snapshot commit (FR21,
 * D15) — the executor's flow stays identical either way.
 *
 * <p>Implements FR4, FR21, FR23 of add-sandbox-core.
 */
public interface RoundEnvironmentSource {

    /**
     * Opens one round: resolves the environment the round runs in and its
     * decision transport.
     *
     * @param request the round's inputs; never null
     * @return the round's handle; never null
     */
    Round openRound(StageExecutor.Request request);

    /**
     * Where the engine takes the token of each round this source opened (FR14, FR21 of
     * define-executor-contract, design D13): the run's current-round cell the source fills as it
     * opens a round in a branch mode (the container source, the git-mode host decoration), or {@link
     * RoundIdentity#inPlace()} for the bare host source, which serves the in-place run only and mints
     * nothing. No default: each source names its own, and the composition root hands it to the
     * engine beside the executor the source serves.
     *
     * @return the identity of this source's rounds; never null
     */
    RoundIdentity identity();

    /** One round's environment, decision transport, and boundary hooks. */
    interface Round {

        /** The environment the round's agent process executes in (FR4). */
        TaskExecutionEnvironment environment();

        /** This round's decision-file path, for the pinpoint {@code Write} allowance in the flags. */
        Path decisionFilePath();

        /** The env fragment naming the decision path for the launched process. */
        Map<String, String> decisionEnvFragment();

        /**
         * The per-round progress listener joined to the executor's own (the
         * sandboxed source's rate-limited mid-round harvest poll, FR5); a no-op
         * by default.
         */
        default AgentProgressListener roundListener() {
            return _ -> {};
        }

        /**
         * Closes the gnome half of the round after the agent process exited:
         * the sandboxed source executes the snapshot commit and harvest here,
         * recording the attempt commit (FR21, D15); the host source does
         * nothing. Runs before {@link #readDecision()}.
         */
        void closeRound();

        /**
         * The decision request the agent wrote this round, if any — read after {@link
         * #closeRound()} through the one tolerant {@link DecisionRequestRead}, so no
         * implementation hands raw decision content on for a second parse (FR6 of
         * define-executor-contract).
         */
        Optional<RoundDecisionRequest> readDecision();

        /** Infrastructure-failure cleanup (NFR-R3): discards round-scoped transport state. */
        void discard();
    }
}
