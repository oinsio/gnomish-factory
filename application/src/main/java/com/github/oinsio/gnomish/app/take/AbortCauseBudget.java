package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The bound every abort-cause text passes before it reaches a tracker write
 * (design D1-D4 of cap-abort-cause-length). The dominant cause producer renders
 * a full exception chain, and a tracker rejects a comment body over its own
 * limit — silently, because both abort writes are best-effort, which is how an
 * oversized cause loses the abort marker and corrupts the consecutive-abort
 * accounting behind the K fuse.
 *
 * <p>Truncation keeps the head and the tail, joined by a marker naming exactly
 * how many characters were dropped: for a rendered exception the head carries
 * the top-level message and throw site, the tail the deepest {@code Caused by:}
 * — the two ends an operator opens the report for. Never a silent cut, and
 * never a cut that drops the end. That mechanism is the carrier's own
 * {@code cappedTo}; this class owns the number it is applied with.
 *
 * <p>This is not the {@code LogText}/{@code FindingsSanitizer} rule at a third
 * site: those cap log lines and plugin findings tail-only, this caps tracker
 * comment bodies head+tail. Different boundary, different invariant.
 *
 * <p>Implements FR1, NFR-O1, UX1 of cap-abort-cause-length.
 */
final class AbortCauseBudget {

    /**
     * Max characters of abort cause any tracker write may carry. Derived from the smallest
     * comment limit among supported and planned trackers — Jira Cloud's 32,767 characters
     * (GitHub's is 65,536) — less headroom for the fuse-trip report's own framing (counts,
     * timestamps, guidance: well under 1,000 characters today) and the abort marker's fixed
     * prefix. Not configurable: it guards a hard API limit, not a preference (design D2).
     */
    static final int BUDGET_CHARS = 28_000;

    private AbortCauseBudget() {}

    /**
     * Bounds {@code cause} to {@link #BUDGET_CHARS} characters, keeping the head and the tail.
     *
     * <p>Carrier in, carrier out (design D13 of type-untrusted-text): the truncation itself belongs
     * to {@link com.github.oinsio.gnomish.untrustedtext.UntrustedText#cappedTo(int)}, because a
     * transformation that keeps the text inside the carrier is not a way out of it and must not
     * widen the exit allowlist. What stays here is the one thing this class owns — the budget the
     * tracker's comment limit dictates — and the single point every tracker-bound cause passes.
     *
     * @param cause the raw cause text, carried; never null
     * @return {@code cause} itself when within the budget, else a carrier of the same provenance
     *     holding its head and tail joined by the omission marker
     */
    static UntrustedText cap(UntrustedText cause) {
        return cause.cappedTo(BUDGET_CHARS);
    }
}
