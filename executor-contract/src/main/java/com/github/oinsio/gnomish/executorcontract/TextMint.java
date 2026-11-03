package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.function.Function;

/**
 * The capture family a read mints its text in, handed to the binding as a read attribute so that
 * every {@link UntrustedText} field of a typed read is minted in the family of the file it came from
 * (FR6, NFR-S2 of define-executor-contract).
 *
 * <p>Implements FR6, NFR-S2 of define-executor-contract.
 */
record TextMint(Function<String, UntrustedText> mint) {}
