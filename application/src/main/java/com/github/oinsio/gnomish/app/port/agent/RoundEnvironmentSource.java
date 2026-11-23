package com.github.oinsio.gnomish.app.port.agent;

import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Where a stage round of any registry entry (the shared round wrapper, {@code
 * ProgramStageExecutor}) gets its execution environment and
 * answer file (the sandbox integration pass of add-sandbox-core): the host
 * default builds a {@code HostTaskExecutionEnvironment} over the {@code
 * DirectoryWorkspace} root with the answer file in that environment's scratch
 * area, outside the workspace; the sandboxed source leases the task's container
 * environment per segment, names the answer file in the branch ({@code
 * EnvelopePaths.answerFile}; FR23, D17), and closes each round with the snapshot
 * commit (FR21, D15) — the executor's flow stays identical either way. Either
 * way the agent is told the path in {@code GNOMISH_ANSWER_FILE}, the executor
 * contract's one answer-file variable (FR9 of extract-agent-round-runner).
 *
 * <p>Implements FR4, FR21, FR23 of add-sandbox-core; FR9 of extract-agent-round-runner.
 */
public interface RoundEnvironmentSource {

    /**
     * Opens one round: resolves the environment the round runs in and its
     * answer file.
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

    /** One round's environment, answer file, and boundary hooks. */
    interface Round {

        /** The environment the round's agent process executes in (FR4). */
        TaskExecutionEnvironment environment();

        /** This round's answer file, for the pinpoint {@code Write} allowance in the flags. */
        Path answerFilePath();

        /** The env fragment naming the answer file for the launched process: {@code GNOMISH_ANSWER_FILE}. */
        Map<String, String> answerEnvFragment();

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
         * recording the attempt commit (FR21, D15) under the subject {@code close}
         * names (design D22 of migrate-verify-to-executor-contract); the host
         * source ignores {@code close} — the git-mode host snapshot is taken
         * after a {@code completed} result only — and reads its answer file before
         * its scratch area leaves with the round. Runs before {@link #readDecision()}.
         *
         * @param close whether the round's executor completed; never null
         */
        void closeRound(RoundClose close);

        /**
         * The decision request the agent wrote to the answer file this round, if any —
         * read at or after {@link #closeRound} through the one tolerant {@link DecisionRequestRead}, so no
         * implementation hands raw decision content on for a second parse (FR6 of
         * define-executor-contract).
         */
        Optional<RoundDecisionRequest> readDecision();

        /** Infrastructure-failure cleanup (NFR-R3): discards round-scoped state. */
        void discard();
    }
}
