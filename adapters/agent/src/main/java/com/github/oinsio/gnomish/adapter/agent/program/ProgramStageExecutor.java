package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.adapter.agent.RoundDenialRead;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link StageExecutor} of a registry entry — a stage that names a {@code program} executor (FR10
 * of define-executor-contract), or a built-in (design D2 of add-command-executor): one round is one
 * {@code start} of the contract executor the entry's {@link RoundExecutorFactory} builds in the
 * environment the
 * mode's round source opens — the sandbox round source in a box, the git-mode host decoration on
 * the host — so the round's token lands in the run's current-round cell exactly as an agent round's
 * does (FR14, FR21, design D13), and the round closes through the same source: the egress guard
 * probed before the launch and again before anything is graded (D14 of tighten-spec-contract), the
 * snapshot commit and harvest of a box round, the environment's denials read at the close and
 * carried on the result beside the executor's own. The contract result is mapped onto the engine's
 * shape by {@link ProgramResultMapping}, the one mapping for a program and a built-in alike; the class
 * is the engine's classifier's to decide. The wrapper is the only round wrapper of a registry entry:
 * what differs between entries is the factory's, never a second wrapper.
 *
 * <p>The request is built by the factory before the round opens — a program's from the frozen
 * law: an unreadable instructions file fails the round before any environment is touched, as the agent round's prompt does. A
 * failure of the round's own mechanics — a dead guard, a close that could not commit — discards the
 * round and is that round's {@code failed/infrastructure}, with the denials drained from it; the
 * engine writes its one operator line.
 *
 * <p>Implements FR5, FR10, FR14, FR21 of define-executor-contract; FR8 of add-command-executor.
 */
public final class ProgramStageExecutor implements StageExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProgramStageExecutor.class);

    private final RoundExecutorFactory executors;
    private final RoundEnvironmentSource rounds;

    /**
     * @param executors what the entry runs in each round: its name, request and executor; never null
     * @param rounds the mode's round source; never null
     */
    public ProgramStageExecutor(RoundExecutorFactory executors, RoundEnvironmentSource rounds) {
        this.executors = executors;
        this.rounds = rounds;
    }

    @Override
    public ExecutionResult execute(Request request) {
        String name = executors.name();
        var key = new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt());
        ExecutorRequest contractRequest = executors.request(request);
        RoundEnvironmentSource.Round round = rounds.openRound(request);
        try {
            round.environment().probeGuard();
            ExecutorResult result = executors.executor(round.environment()).start(contractRequest);
            round.environment().probeGuard();
            round.closeRound();
            var denials = RoundDenialRead.onFinish(round);
            // A program is handed no decision file; the read is the source's hygiene of its round.
            round.readDecision();
            return ProgramResultMapping.toEngine(name, key, result, denials);
        } catch (RuntimeException e) {
            // throwable-not-subject: the round's failed/infrastructure carries the cause's class and
            //     message; the engine writes the one operator line for it.
            log.debug("the round of program executor '{}' failed before its result was mapped", name, e);
            // As the agent round does (ExecutorRoundExecution): the round is discarded exactly once,
            // after the denials were drained from it — the host discard disposes the environment the
            // read asks.
            var denials = RoundDenialRead.onFailure(round);
            round.discard();
            var failed = new ExecutionResult.Failed(
                    ExecutionResult.FailureClass.INFRASTRUCTURE,
                    UntrustedText.subprocess(
                            "program executor '" + name + "': the round failed before its result was read: " + e),
                    List.of());
            return new ExecutionResult(failed, null, ExecutorUsage.none(), new ToolTrace(key, List.of()), denials);
        }
    }
}
