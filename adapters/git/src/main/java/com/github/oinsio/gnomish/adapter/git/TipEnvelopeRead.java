package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The result of {@link TipEnvelopeReader#read}: either a shape with nothing to render, or the
 * shape plus both envelope texts a caller can parse into its own result type.
 */
sealed interface TipEnvelopeRead {

    /** The tip's shape carries no readable state (FR16 of harden-task-branch-contract); render the shape alone. */
    record NoState(BranchShape shape) implements TipEnvelopeRead {}

    /** The tip's shape carries state, and both envelopes were read from it. */
    record Loaded(BranchShape shape, UntrustedText taskJson, UntrustedText stateJson) implements TipEnvelopeRead {}
}
