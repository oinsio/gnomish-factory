package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * What one admission came to (design D18 of tighten-spec-contract): the branch tip after the move,
 * or the refusal — a boundary violation, or a verdict git could not produce — with the branch where
 * it was, so nothing refused ever became branch state.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 */
sealed interface AdmittedRange {

    /** The range is on the branch; {@code tip} is the branch tip that carries it. */
    record Admitted(String tip) implements AdmittedRange {}

    /** The range broke the boundary; the branch did not move. */
    record Refused(UntrustedText reason) implements AdmittedRange {}

    /** The range could not be judged, or the move was refused by git; the branch did not move. */
    record Unverified(UntrustedText evidence) implements AdmittedRange {}
}
