package com.github.oinsio.gnomish.app.port.check;

import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.pendingkey.PendingKey;

/**
 * The role of a verdict executor whose pending keys live in the factory run's own table (design D14
 * of migrate-verify-to-executor-contract, as amended by D20 and on 2026-11-13): before the first poll
 * of a key the resume path read from the open round's pending record, the check round hands the key
 * to the executor, which enters it into the run's table for its check. Only the in-process program adapter takes this role; a built-in's
 * key names a run the remote holds, so every instance can poll it and adopts nothing.
 *
 * <p>Adoption is a Java-side concern of the adapter, never a contract operation: the {@code Executor}
 * interface of the executor contract is unchanged, and no program sees an adoption (D17). A key
 * reaches {@link #adopt} only from the pending record through the resume path ({@code RECORDED}),
 * never from the request on the wire.
 *
 * <p>Implements FR3, FR11, NFR-R1 of migrate-verify-to-executor-contract.
 */
public interface PendingAdoption {

    /**
     * Makes {@code key} a key of this run, for the bound check, before its first poll. What the adoption found is
     * answered by that poll, never thrown: a key that could not be adopted is polled into a {@code
     * failed/infrastructure} result naming it, with no process spawned.
     *
     * @param request the request the key is about to be polled with; never null
     * @param key the key the open round's pending record names; never null
     */
    void adopt(ExecutorRequest request, PendingKey key);
}
