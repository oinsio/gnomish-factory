package com.github.oinsio.gnomish.architecture.seeded;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * A class that reads {@link UntrustedText#forParsing()} while carrying no
 * {@code @UntrustedParser} — the violation rule (a2) of {@code UntrustedTextGateSpec} exists to
 * fail on (FR10, design D11 of type-untrusted-text). Converting the text is not enough: the point
 * of the second annotation is that the set of classes reading captured bytes is declared, so a
 * reviewer can read it.
 */
public final class ForParsingSeed {

    private ForParsingSeed() {}

    /**
     * Parses an exit code out of captured output without declaring itself a parser.
     *
     * @param captured the carrier to read; never null
     * @return how many characters the captured text holds
     */
    public static int undeclared(UntrustedText captured) {
        return captured.forParsing().length();
    }
}
