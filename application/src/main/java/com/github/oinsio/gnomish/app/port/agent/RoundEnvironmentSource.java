package com.github.oinsio.gnomish.app.port.agent;

import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.nio.file.Path;
import java.util.Map;

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
 * <p>The source holds its open round (design D11, D14 of extract-agent-round-runner, as amended
 * 2026-11-25): a round's {@code start} and all its polls are one round — one open, one token, one
 * close. {@link #openRound} opens a round and is refused while one is open; {@link #continueRound}
 * hands a poll the open round and opens, mints and materializes nothing; going {@code pending} is
 * {@link Round#goPending()}, which keeps the round open; {@link Round#closeRound} takes a terminal
 * outcome only; the close or the discard ends the round the source holds.
 *
 * <p>Implements FR4, FR21, FR23 of add-sandbox-core; FR9, FR10 of extract-agent-round-runner.
 */
public interface RoundEnvironmentSource {

    /**
     * Opens one round — a round's {@code start}: resolves the environment the round runs in and its
     * answer file, and in a branch mode mints the round's token. The source holds the round until its
     * close or its discard.
     *
     * @param request the round's inputs; never null
     * @return the round's handle; never null
     * @throws IllegalStateException if a round of this source is open: refused before anything is
     *     minted or materialized (D14 as amended 2026-11-25)
     */
    Round openRound(StageExecutor.Request request);

    /**
     * The open round a poll continues (design D11, D14 of extract-agent-round-runner, as amended
     * 2026-11-25): the round this source opened and still holds, or — on a resuming instance, whose
     * source opened nothing — the pending round the run's round cell was restored with, built over the
     * token the cell holds, in an environment at the registered commit. Nothing is minted, and a held
     * round's environment, scratch area, answer path and listener are the ones it opened with.
     *
     * @param request the poll's inputs; never null
     * @return the open round; never null
     * @throws NoOpenRoundException if no round is open and the cell holds no restored pending round
     */
    Round continueRound(StageExecutor.Request request);

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
         * The round's {@code start} answered {@code pending} (design D11 of
         * extract-agent-round-runner, as amended 2026-11-25): the round's working copy is registered
         * once as the pending-round snapshot — by the sandboxed source here, in-box with its harvest
         * into the run's cell; on the host by the stage chain's snapshot decoration, so the host
         * source does nothing — and the round stays open: its environment, scratch area and listeners
         * are kept for the polls {@link RoundEnvironmentSource#continueRound} hands it to. Never
         * called on a poll's result.
         */
        void goPending();

        /**
         * Closes the gnome half of the round after its executor's terminal result — a {@code start}'s,
         * or the terminal poll's of a pending round: the sandboxed source executes the snapshot commit
         * and harvest here, recording the attempt commit (FR21, D15) under the subject {@code close}
         * names (design D22 of migrate-verify-to-executor-contract); the host source ignores {@code
         * close} — the git-mode host snapshot is taken after a {@code completed} result only — and
         * disposes the round's environment with its scratch area. The source no longer holds the
         * round. A {@link #discard()} after the close changes nothing (design D14 of
         * extract-agent-round-runner).
         *
         * @param close the terminal outcome; a pending round is never closed (D11 as amended)
         */
        void closeRound(TerminalClose close);

        /**
         * Infrastructure-failure cleanup (NFR-R3): discards round-scoped state, so a discard after
         * an open leaves no scratch area of the round's own behind (design D14 of
         * extract-agent-round-runner), and the source no longer holds the round. The round's answer
         * is read by its executor, never here.
         */
        void discard();
    }
}
