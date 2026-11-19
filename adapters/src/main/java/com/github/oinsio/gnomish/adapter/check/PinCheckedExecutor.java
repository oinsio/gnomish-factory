package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import java.util.SortedSet;

/**
 * The pin decorator over a check's bound executor (design D8, D17 of
 * migrate-verify-to-executor-contract): every {@code start} and every {@code poll} — each a point of
 * use, a resumed {@code poll} with no {@code start} in its process included — first asks the run's
 * {@link PinCheck} about the check's pin set at the check's working copy, and reaches the executor
 * only when the pin passes; otherwise the pin's refusal is the round's result. Built by {@link
 * PinCheck#guarding} only, for a non-empty pin set.
 *
 * <p>Implements FR16 of add-sandbox-core; FR6, FR16 of migrate-verify-to-executor-contract.
 */
final class PinCheckedExecutor implements Executor {

    private final Executor delegate;
    private final PinCheck pin;
    private final String checkId;
    private final SortedSet<String> pins;
    private final Workspace workspace;

    PinCheckedExecutor(Executor delegate, PinCheck pin, String checkId, SortedSet<String> pins, Workspace workspace) {
        this.delegate = delegate;
        this.pin = pin;
        this.checkId = checkId;
        this.pins = pins;
        this.workspace = workspace;
    }

    @Override
    public Describe describe() {
        return delegate.describe();
    }

    @Override
    public ExecutorResult start(ExecutorRequest request) {
        ExecutorResult refused = pin.refusal(checkId, pins, workspace);
        return refused != null ? refused : delegate.start(request);
    }

    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        ExecutorResult refused = pin.refusal(checkId, pins, workspace);
        return refused != null ? refused : delegate.poll(request, key);
    }
}
