package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.pendingkey.PendingKey;

/**
 * A {@code program} verdict check bound with no box to run in ({@link ProgramCheckBinding}): every
 * operation answers the one {@code failed/infrastructure} result naming why, and no process is
 * spawned. It takes no adoption role, so a recorded key is polled into the same answer.
 *
 * <p>Implements FR3, NFR-R1 of migrate-verify-to-executor-contract.
 *
 * @param describe the program's describe answer; never null
 * @param refusal the result every operation answers; never null
 */
record UnboxedProgram(Describe describe, ExecutorResult refusal) implements Executor {

    @Override
    public ExecutorResult start(ExecutorRequest request) {
        return refusal;
    }

    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        return refusal;
    }
}
