package com.github.oinsio.gnomish.app.port.agent;

/**
 * How a round closes (design D22 of migrate-verify-to-executor-contract, human decision 2026-11-12):
 * whether the gnome half it closes may be verified. The round's executor names it at the close,
 * before its result is returned; the container's snapshot step turns it into the snapshot's subject,
 * so a pickup never resumes a round its executor did not complete as an interrupted verification.
 *
 * <p>Implements FR19, NFR-R1 of migrate-verify-to-executor-contract; FR10 of
 * extract-agent-round-runner (design D11: the pending-round close).
 */
public enum RoundClose {

    /**
     * The round's executor completed: an agent round always (its result is {@code completed} or a
     * decision request the snapshot carries), a program round exactly when its result maps to
     * {@code completed}. The snapshot is {@code gnomish: snapshot <stage>#<round> <token>}.
     */
    VERIFIABLE,

    /**
     * The round's executor did not complete (a program that answered {@code failed}, or {@code
     * pending} in a role other than {@code transform}). The snapshot is {@code gnomish:
     * unverified-snapshot <stage>#<round> <token>}, which no pickup reads as an interrupted
     * verification.
     */
    UNVERIFIABLE,

    /**
     * The round's {@code transform} executor answered {@code pending} (design D11 of
     * extract-agent-round-runner): the round stays open, and its pending round commit lands on this
     * snapshot. The snapshot is {@code gnomish: pending-snapshot <stage>#<round> <token>}, which no
     * pickup reads as a finished round to verify.
     */
    PENDING
}
