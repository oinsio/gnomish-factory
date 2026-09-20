package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The empty {@code details} of a verdict or poll status with no captured output to quote. Empty
 * text has no source, so it is minted in the factory's own family rather than in the family of
 * whatever capture sits beside it (design D3).
 *
 * <p>Shared across {@code adapter.check} and its {@code http} sub-package so every built-in check
 * client mints the same empty carrier rather than repeating the constant per file.
 */
public final class NoCheckDetails {

    public static final UntrustedText NO_DETAILS = UntrustedText.factory("");

    private NoCheckDetails() {}
}
