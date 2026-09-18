package com.github.oinsio.gnomish.architecture.seeded;

import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The other half of rule (a)'s seeded pair: the same raw read, in a class that declares itself an
 * exit owner. Without it a green rule could mean "the annotation is ignored" as easily as "the
 * exemption works" (FR3, design D2 of type-untrusted-text).
 */
@UntrustedExit
public final class AnnotatedExitSeed {

    private AnnotatedExitSeed() {}

    /**
     * Writes a carrier to a machine medium the way an annotated exit owner may.
     *
     * @param text the carrier to write; never null
     * @return its raw text
     */
    public static String write(UntrustedText text) {
        return text.raw();
    }
}
