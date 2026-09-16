package com.github.oinsio.gnomish.untrustedtext

/**
 * The character-table ranges restated as data rather than reused from the production predicate: a
 * spec that imported the table could only prove the table agrees with itself.
 *
 * <p>Kept in sync with {@link TextSafetyConsoleSpec.CharacterTableProbe#hostile} and the private
 * {@code neutralized} predicate in {@link TextSafetyIdempotenceSpec}: both wrap {@link #inTable}, the
 * two planes differing only in whether the line feed counts — the log plane neutralizes it, the
 * console plane is the one plane that keeps it.
 */
class HostileCodePoints {

    static boolean inTable(int cp) {
        cp < 0x20
                || (cp >= 0x7F && cp <= 0x9F)
                || (cp >= 0x200B && cp <= 0x200F)
                || (cp >= 0x2060 && cp <= 0x2064)
                || cp == 0xFEFF
                || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2066 && cp <= 0x2069)
                || (cp >= 0xE0000 && cp <= 0xE007F)
    }
}
