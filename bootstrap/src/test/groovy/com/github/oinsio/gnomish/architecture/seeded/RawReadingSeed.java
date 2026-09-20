package com.github.oinsio.gnomish.architecture.seeded;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * A class that reads {@link UntrustedText#raw()} while carrying no {@code @UntrustedExit} — the
 * violation rule (a) of {@code UntrustedTextGateSpec} exists to fail on (FR3, design D2 of
 * type-untrusted-text).
 */
public final class RawReadingSeed {

    private RawReadingSeed() {}

    /**
     * Launders a carrier into a plain string the way the rule forbids.
     *
     * @param text the carrier to read; never null
     * @return its raw text, unneutralized
     */
    public static String launder(UntrustedText text) {
        return text.raw();
    }
}
