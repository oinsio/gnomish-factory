package com.github.oinsio.gnomish.architecture.seeded;

import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;

/**
 * The seeded subject for the warrant half of rule (a): a class that declares itself an exit owner
 * and never reads the carrier's raw text, so its marker widens the allowlist for nothing — the
 * membership rule design D2 states, "the {@code raw()} call, not the family" (FR3 of
 * type-untrusted-text). Three production classes carried exactly this shape until the warrant spec
 * was written.
 */
@UntrustedExit
public final class WarrantlessExitSeed {

    private WarrantlessExitSeed() {}

    /**
     * Writes a value that was never a carrier, which is what makes the marker above unwarranted.
     *
     * @param token a wire token this module minted itself; never null
     * @return the token, unchanged
     */
    public static String write(String token) {
        return token;
    }
}
