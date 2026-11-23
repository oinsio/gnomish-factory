package com.github.oinsio.gnomish.domain.engine;

/**
 * What a {@link PendingPollRecord} is the pending poll of (design D11 of extract-agent-round-runner):
 * a verdict {@link Check} of the open round, as {@code migrate-verify-to-executor-contract} built it,
 * or the open {@link Round} itself — a {@code transform} round whose executor answered {@code
 * pending}. The round's identity is not part of the subject: the record already names its round by
 * attempt and token, so a {@code Round} subject carries nothing of its own.
 *
 * <p>Sealed, so every reader switches over both subjects with no default arm: a new subject fails to
 * compile until each reader decides what it means for it (the single-owner row {@code PendingPoll}
 * of extract-agent-round-runner, "Enforced by").
 *
 * <p>Implements FR10, NFR-R3 of extract-agent-round-runner.
 */
public sealed interface PendingSubject {

    /** The subject of a {@code transform} round's pending poll; every {@code Round} equals another. */
    PendingSubject ROUND = new Round();

    /**
     * A verdict check of the open round whose executor answered {@code pending}.
     *
     * @param ref the check's identity in the round, its index in full since a resume picks the check
     *     by it; never null
     */
    record Check(CheckRef ref) implements PendingSubject {}

    /** The open {@code transform} round itself, named by the record's attempt and token. */
    record Round() implements PendingSubject {}
}
