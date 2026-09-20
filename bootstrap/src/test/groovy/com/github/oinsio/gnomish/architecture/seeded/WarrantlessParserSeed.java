package com.github.oinsio.gnomish.architecture.seeded;

import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;

/**
 * The seeded subject for the warrant half of rule (a2): a class that declares itself a parser and
 * never reads the carrier's captured bytes, so its marker widens the parser allowlist for nothing.
 * Design D11 states membership "by return type, not by intent"; the half of that criterion a
 * bytecode scan can decide is the {@code forParsing()} call itself, so a marker with no such call
 * is warrantless (FR10 of type-untrusted-text). The twin of {@link WarrantlessExitSeed}, written
 * because rule (a2)'s set is three times rule (a)'s and had no warrant check at all.
 */
@UntrustedParser
public final class WarrantlessParserSeed {

    private WarrantlessParserSeed() {}

    /**
     * Parses a value the factory minted itself, which is what makes the marker above unwarranted.
     *
     * @param wireToken a token this module wrote; never null
     * @return the token's length
     */
    public static int width(String wireToken) {
        return wireToken.length();
    }
}
