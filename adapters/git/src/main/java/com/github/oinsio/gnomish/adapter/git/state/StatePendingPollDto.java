package com.github.oinsio.gnomish.adapter.git.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * One element of the {@code state.json} contract's optional root array {@code pendingPolls}: the
 * pending poll of one verdict check of the open round, written so any instance can resume it
 * (design D20 as amended 2026-11-12, D21 of migrate-verify-to-executor-contract). Shape: {@code
 * {"check": {"index": <the check's zero-based position>, "ref": "<its label>"}, "attempt": <the
 * round's attempt number>, "token": "<the round token's commit id>", "key": "<the key>", "since":
 * "<ISO-8601 instant>"}}. The element names its round itself, by attempt and token; {@code token}
 * is omitted only for the in-place run, which has no branch.
 *
 * <p>Every component binds to {@code null} when the document omits it, so that {@link
 * StatePendingPollMapper#fromDtos} can refuse a missing field as a malformed state file rather
 * than the binder failing with a generic message.
 *
 * <p>Implements FR2, FR20 of migrate-verify-to-executor-contract.
 *
 * @param check the check whose executor answered {@code pending}
 * @param attempt the attempt number of the round the poll belongs to
 * @param token the round token's commit id; {@code null} in the in-place run
 * @param key the pending key, as the leaf's {@code PendingKey} value
 * @param since the instant the first {@code pending} answer was taken, ISO-8601 UTC
 */
public record StatePendingPollDto(
        @Nullable Check check,
        @Nullable Integer attempt,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String token,
        @Nullable String key,
        @Nullable String since) {

    /**
     * The check's identity in full: unlike the checks list, which carries the label only, the
     * index is written because the resume picks the check by it.
     *
     * @param index the check's zero-based position in the stage's verify list
     * @param ref the check's label, spelled as {@link StateCheckDto#ref()}
     */
    public record Check(@Nullable Integer index, @Nullable String ref) {}
}
