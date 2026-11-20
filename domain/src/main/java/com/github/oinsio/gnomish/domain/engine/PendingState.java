package com.github.oinsio.gnomish.domain.engine;

/**
 * What a {@link ExecutionResult.Pending} status says of the subjects it awaits: the fact the engine
 * classifies a check's timeout by (design D19 of migrate-verify-to-executor-contract). At the
 * timeout the engine reads the last pending answer: {@link #UNREPORTED} or {@link #UNOBSERVED}
 * resolve as cannot-verify, since no verdict was obtained; {@link #RUNNING} keeps the check's
 * declared timeout class. A pending result that names no state is {@link #RUNNING}.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.executorcontract.PendingState}: every
 * contract pending state has exactly one counterpart here, and the absent wire state is {@link
 * #RUNNING} (no shared classpath — {@code :domain} reaches JDK-only leafs only; the registry row
 * of {@link ExecutionResult} in {@code manual-sync-pairs.md} is the index).
 *
 * <p>Implements FR2, FR4, FR9 of migrate-verify-to-executor-contract.
 */
public enum PendingState {
    /** Every awaited subject was seen, and one is still running; also a result naming no state. */
    RUNNING,
    /** An awaited subject never appeared on the polled commit. */
    UNREPORTED,
    /** The source could not be read this time, such as for a rate limit. */
    UNOBSERVED
}
