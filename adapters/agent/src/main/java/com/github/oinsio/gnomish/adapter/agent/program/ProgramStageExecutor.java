package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.adapter.agent.RoundDenialRead;
import com.github.oinsio.gnomish.app.port.agent.NoOpenRoundException;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.check.PendingAdoption;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link StageExecutor} of a registry entry — a stage that names a {@code program} executor (FR10
 * of define-executor-contract), a built-in (design D2 of add-command-executor), or an agent stage through
 * the {@code claude} entry (task 5.1a of extract-agent-round-runner): one round is one {@code start}
 * and, for a {@code transform} round that answered {@code pending}, every {@code poll} the engine
 * makes of it until a terminal result — one round, one open, one token, one close (D11 of
 * extract-agent-round-runner, as amended 2026-11-25). {@link #execute} opens the round; {@link #poll}
 * takes the open round from the source ({@link RoundEnvironmentSource#continueRound}) and opens,
 * mints and closes nothing on a {@code pending} answer, a poll with no open round being the poll's
 * {@code failed/infrastructure} naming the missing round; the operation is chosen by {@link
 * PendingTurnRun}, handed the bound executor's {@link PendingAdoption} role for a recorded key. A
 * {@code start} that answers {@code pending} goes pending ({@link
 * RoundEnvironmentSource.Round#goPending()}) and the round stays open; the round's denials are read
 * once, at its one terminal close, and join the terminal result. Each request runs the
 * contract executor the entry's {@link RoundExecutorFactory} builds in the
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
 * extract-agent-round-runner; FR10, FR11 of extract-agent-round-runner (a poll continues the open round).
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
        ExecutorRequest built = executors.request(request);
        return inRound(request, built, rounds.openRound(request), null);
    }

    /**
     * The poll of a pending {@code transform} round, in the round its {@code start} opened (D11 as
     * amended 2026-11-25): no open, no mint, and a close on a terminal result only.
     */
    @Override
    public ExecutionResult poll(Request request, PendingTurn turn) {
        ExecutorRequest built = executors.request(request);
        RoundEnvironmentSource.Round round;
        try {
            round = rounds.continueRound(request);
        } catch (NoOpenRoundException e) {
            // throwable-not-subject: the refusal is the poll's failed/infrastructure; the engine
            //     writes the one operator line for it.
            log.debug("executor '{}' was polled with no open round", executors.name(), e);
            return failed(
                    request,
                    UntrustedText.factory(executors.subject().named(executors.name()) + e.getMessage()),
                    List.of());
        }
        return inRound(request, built, round, turn);
    }

    private ExecutionResult inRound(
            Request request, ExecutorRequest built, RoundEnvironmentSource.Round round, @Nullable PendingTurn turn) {
        String name = executors.name();
        ProgramFailures.Subject subject = executors.subject();
        var key = new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt());
        try {
            ExecutorRequest contractRequest = executors.opened(built, round);
            // The guard is probed around the request by its one owner (design D13 of
            // extract-agent-round-runner), never here.
            Executor bound = executors.executor(round);
            var executor = GuardedExecutor.over(subject, name, round.environment(), bound);
            // The engine's turn selects the operation (D11 of extract-agent-round-runner): a round's
            // start, or a poll of the key its pending round went pending with — a recorded key
            // first handed to the bound executor's adoption role, when it takes one.
            PendingAdoption adoption = bound instanceof PendingAdoption role ? role : null;
            ExecutorResult result = PendingTurnRun.run(executor, adoption, contractRequest, turn);
            if (executor.foundGuardDead()) {
                // A round whose guard died broke its cage: nothing of it is committed (D14 of
                // tighten-spec-contract). It is discarded exactly once, after its denials were drained.
                var denials = RoundDenialRead.onFailure(round);
                round.discard();
                return ProgramResultMapping.toEngine(name, contractRequest.role(), key, result, denials);
            }
            if (ProgramResultMapping.goesPending(contractRequest.role(), result)) {
                // A pending round stays open (D11 as amended 2026-11-25): a start registers the
                // pending-round snapshot, a poll writes nothing; no denials are read until the close.
                if (turn == null) {
                    round.goPending();
                }
                return ProgramResultMapping.toEngine(name, contractRequest.role(), key, result, List.of());
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
            return failed(
                    request,
                    UntrustedText.subprocess(subject.named(name) + "the round failed before its result was read: " + e),
                    denials);
        }
    }

    private static ExecutionResult failed(Request request, UntrustedText reason, List<Denial> denials) {
        var key = new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt());
        var failed = new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, reason, List.of(), null);
        return new ExecutionResult(failed, null, ExecutorUsage.none(), new ToolTrace(key, List.of()), denials);
    }
}
