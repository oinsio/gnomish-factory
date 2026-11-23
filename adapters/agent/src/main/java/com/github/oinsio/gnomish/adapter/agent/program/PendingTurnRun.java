package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.app.port.check.PendingAdoption;
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import org.jspecify.annotations.Nullable;

/**
 * The one choice of a contract operation by the engine's {@link PendingTurn} (design D14 of
 * migrate-verify-to-executor-contract; D11 of extract-agent-round-runner): no turn is a {@code start};
 * a key this run issued ({@link PendingTurn.Origin#ISSUED ISSUED}) is a {@code poll} of that key; a key
 * the resume path read from the pending record ({@link PendingTurn.Origin#RECORDED RECORDED}) is first
 * handed to the executor's {@link PendingAdoption}, when it takes that role, then polled. A check round
 * ({@code CheckRound}) and a stage round ({@link ProgramStageExecutor}) both choose through it, so the
 * rule is written once for both roles.
 *
 * <p>Implements FR2, FR3 of migrate-verify-to-executor-contract; FR10, FR11 of
 * extract-agent-round-runner.
 */
public final class PendingTurnRun {

    private PendingTurnRun() {}

    /**
     * Sends {@code request} as the operation {@code turn} selects.
     *
     * @param executor the bound executor; never null
     * @param adoption the same executor's adoption role, or {@code null} when it takes none
     * @param request the contract request; never null
     * @param turn the pending turn the request continues, or {@code null} for a {@code start}
     * @return the executor's result; never null
     */
    public static ExecutorResult run(
            Executor executor,
            @Nullable PendingAdoption adoption,
            ExecutorRequest request,
            @Nullable PendingTurn turn) {
        if (turn == null) {
            return executor.start(request);
        }
        if (turn.origin() == PendingTurn.Origin.RECORDED && adoption != null) {
            adoption.adopt(request, turn.key());
        }
        return executor.poll(request, turn.key());
    }
}
