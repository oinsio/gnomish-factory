package com.github.oinsio.gnomish.httpegress;

import java.time.Duration;

/**
 * One consumer's bounds on an exchange (FR15, design D16 of migrate-verify-to-executor-contract,
 * "Per consumer, not per leaf"): how long one request may take, headers and body together, measured
 * from the send; how many body bytes it may read; and whether a body over that bound is refused or
 * read as its tail.
 *
 * <p>Implements FR15, NFR-R5 of migrate-verify-to-executor-contract.
 *
 * @param timeout the bound on one request, from the send to the last body byte; positive
 * @param maxBodyBytes the response bound in bytes; positive
 * @param tail whether a body over the bound is read as its last {@code maxBodyBytes} bytes rather
 *     than refused as an {@link ExchangeLimitException}
 */
public record ExchangeBounds(Duration timeout, int maxBodyBytes, boolean tail) {

    public ExchangeBounds {
        if (timeout.isNegative() || timeout.isZero() || maxBodyBytes <= 0) {
            throw new IllegalArgumentException("an exchange bound is positive");
        }
    }

    /**
     * Bounds that refuse a body over {@code maxBodyBytes}.
     *
     * @param timeout the bound on one request; positive
     * @param maxBodyBytes the response bound; positive
     * @return the bounds
     */
    public static ExchangeBounds whole(Duration timeout, int maxBodyBytes) {
        return new ExchangeBounds(timeout, maxBodyBytes, false);
    }
}
