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
 * of define-executor-contract), a built-in (design D2 of add-command-executor), or an agent stage through
 * the {@code claude} entry (task 5.1a of extract-agent-round-runner): one round is one
 * {@code start} of the contract executor the entry's {@link RoundExecutorFactory} builds in the
 * environment the
 * mode's round source opens — the sandbox round source in a box, the git-mode host decoration on
 * the host — so the round's token lands in the run's current-round cell exactly as an agent round's
 * does (FR14, FR21, design D13), and the round closes through the same source: the executor bound
 * behind {@link GuardedExecutor}, the one owner of the egress guard's probe before the request and
 * after it (D13 of extract-agent-round-runner; D14 of tighten-spec-contract) — a round whose guard
 * was found dead is discarded, never closed — the snapshot commit and harvest of a box round, the environment's denials read at the close and
 * carried on the result beside the executor's own. The contract result is mapped onto the engine's
 * shape by {@link ProgramResultMapping}, the one mapping for a program, a built-in and the agent alike; the class
 * is the engine's classifier's to decide. The wrapper is the only round wrapper of a registry entry:
 * what differs between entries is the factory's, never a second wrapper.
 *
 * <p>The request is built by the factory before the round opens — a program's from the frozen
 * law: an unreadable instructions file fails the round before any environment is touched, as the agent round's prompt does —
 * and completed by the open round when the factory takes something from it ({@link
 * RoundExecutorFactory#opened}: the agent entry's answer file); the executor is the factory's binding
 * of the open round ({@link RoundExecutorFactory#executor(RoundEnvironmentSource.Round)}), and the
 * wrapper's own refusals name it by the factory's {@link RoundExecutorFactory#subject()}. A
 * failure of the round's own mechanics — a dead guard, a close that could not commit — discards the
 * round and is that round's {@code failed/infrastructure}, with the denials drained from it; the
 * engine writes its one operator line.
 *
 * <p>Implements FR5, FR10, FR14, FR21 of define-executor-contract; FR8 of add-command-executor; FR14, FR15 of
 * extract-agent-round-runner.
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
        ProgramFailures.Subject subject = executors.subject();
        var key = new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt());
        ExecutorRequest built = executors.request(request);
        RoundEnvironmentSource.Round round = rounds.openRound(request);
        try {
            ExecutorRequest contractRequest = executors.opened(built, round);
            // The guard is probed around the request by its one owner (design D13 of
            // extract-agent-round-runner), never here.
            var executor = GuardedExecutor.over(subject, name, round.environment(), executors.executor(round));
            ExecutorResult result = executor.start(contractRequest);
            if (executor.foundGuardDead()) {
                // A round whose guard died broke its cage: nothing of it is committed (D14 of
                // tighten-spec-contract). It is discarded exactly once, after its denials were drained.
                var denials = RoundDenialRead.onFailure(round);
                round.discard();
                return ProgramResultMapping.toEngine(name, contractRequest.role(), key, result, denials);
            }
            // The close names its outcome by the one mapping's own judgement, made before it (design
            // D22 of migrate-verify-to-executor-contract): a round its program did not complete is
            // never resumed as an interrupted verification. The denials are read after the close
            // and still join the mapped result.
            round.closeRound(ProgramResultMapping.closeOf(contractRequest.role(), result));
            var denials = RoundDenialRead.onFinish(round);
            return ProgramResultMapping.toEngine(name, contractRequest.role(), key, result, denials);
        } catch (RuntimeException e) {
            // throwable-not-subject: the round's failed/infrastructure carries the cause's class and
            //     message; the engine writes the one operator line for it.
            log.debug("the round of executor '{}' failed before its result was mapped", name, e);
            // The round is discarded exactly once, after the denials were drained from it — the host
            // discard disposes the environment the read asks.
            var denials = RoundDenialRead.onFailure(round);
            round.discard();
            var failed = new ExecutionResult.Failed(
                    ExecutionResult.FailureClass.INFRASTRUCTURE,
                    UntrustedText.subprocess(subject.named(name) + "the round failed before its result was read: " + e),
                    List.of(),
                    null);
            return new ExecutionResult(failed, null, ExecutorUsage.none(), new ToolTrace(key, List.of()), denials);
        }
    }
}
