package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The pending poll of one verdict check, an entry of the open round's list on {@link
 * TaskState#pendingPolls()} so that any factory instance can resume it: the check whose executor
 * answered {@code pending}, the attempt and the round token of the round it ran in, the key the
 * executor returned, and the instant the first {@code pending} answer was taken (design D2, D7 and
 * D20 as amended 2026-11-12, D21 of migrate-verify-to-executor-contract). The key, the check and
 * the round's identity are only true together, so they land in one commit (crash-consistency item
 * 4), and the entry names its round by the token the orchestrator minted (item 13), never by its
 * place in a list.
 *
 * <p>The key is the {@code :pendingkey} leaf's type, never a {@code String}: a record cannot hold a
 * key the rule refuses, so the state file's cap on it is {@link PendingKey#MAX_LENGTH} by
 * construction. The record holds no wait hint and no pending state (D19, D7 as amended): a resume
 * polls the key at once. The {@code check} carries its zero-based position in full, since a resume
 * picks the check by it. Inert value data compared by content.
 *
 * <p>Implements FR2, FR20, NFR-R1 of migrate-verify-to-executor-contract.
 *
 * @param check the check whose executor answered {@code pending}; never null
 * @param attempt the attempt key's number of the round the poll belongs to; never negative
 * @param token the tip the round opened on, in a branch mode; {@code null} only in the in-place
 *     run, which has no branch
 * @param key the key the executor returned, the one the next poll names; never null
 * @param since the instant the first {@code pending} answer was taken; never null
 */
public record PendingPollRecord(
        CheckRef check, int attempt, @Nullable RoundToken token, PendingKey key, Instant since) {

    public PendingPollRecord {
        requireNonNegative(attempt);
    }

    /**
     * Fails fast on a negative {@code attempt}: an attempt number counts rounds from zero. Kept as
     * an explicit static method rather than inline in the compact constructor: PIT's record filter
     * suppresses all mutations inside a record's canonical constructor.
     *
     * <p>{@code @DoNotMutate} for the JVMTI redefinition limit ({@code testing.md}): since the
     * record carries a type-annotated component ({@code @Nullable RoundToken token}, task 4.1r),
     * PIT's Gregor engine crashes its minion (RUN_ERROR, not a test gap) redefining this record's
     * static method (hcoles/pitest#1285, the Record-attribute restriction, as on {@link
     * AttemptRecord}). Otherwise fully covered by {@code PendingPollRecordSpec}, which builds
     * attempt {@code 0} and refuses {@code -1}.
     */
    @DoNotMutate
    private static void requireNonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("PendingPollRecord.attempt must not be negative");
        }
    }
}
