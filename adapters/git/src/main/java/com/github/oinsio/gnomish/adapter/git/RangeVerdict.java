package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The three outcomes of judging gnome content at an admission point, never two (FR13 of
 * harden-logging-observability; design D18 of tighten-spec-contract): clean, violated, and
 * cannot-verify — a git invocation that did not produce its evidence is never read as clean, and
 * never attributed to the gnome as a violation.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 */
sealed interface RangeVerdict {

    /** Nothing in the judged content breaks the boundary. */
    record Clean() implements RangeVerdict {}

    /** The boundary is broken; {@code reason} names the commit or the path. */
    record Violated(UntrustedText reason) implements RangeVerdict {}

    /** The evidence could not be produced; {@code evidence} carries git's own account. */
    record CannotVerify(UntrustedText evidence) implements RangeVerdict {}
}
