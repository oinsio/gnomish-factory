package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.adapter.environment.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Where a {@link CliStageExecutor} round gets its execution environment and
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
         * The raw decision content the agent wrote this round, if any — read
         * after {@link #closeRound()}.
         */
        Optional<String> readDecision();

        /** Infrastructure-failure cleanup (NFR-R3): discards round-scoped transport state. */
        void discard();
    }
}
