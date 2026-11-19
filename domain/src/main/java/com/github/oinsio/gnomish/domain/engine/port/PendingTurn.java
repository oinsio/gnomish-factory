package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.pendingkey.PendingKey;

/**
 * The pending turn a check round continues (design D14 of migrate-verify-to-executor-contract): the
 * key an executor returned with {@code pending}, and where the engine took it from. A request with
 * no turn is a {@code start}; with one, the check round polls the key. The key is the {@code
 * :pendingkey} leaf's type, never a bare {@code String}, so the check round hands it to the poll as
 * it is (design D20).
 *
 * <p>Implements FR2, FR3 of migrate-verify-to-executor-contract.
 *
 * @param key the executor's opaque continuation key; never null
 * @param origin where the engine took the key from; never null
 */
public record PendingTurn(PendingKey key, Origin origin) {

    /** Where the engine took a pending key from. */
    public enum Origin {
        /** A key this run's own {@code start} or {@code poll} returned. */
        ISSUED,
        /** The key read from the pending record on resume, set by the resume path only. */
        RECORDED
    }
}
