package com.github.oinsio.gnomish.testsupport

/**
 * What "neutralized" means when a spec asserts it on rendered text: nothing a terminal executes,
 * nothing that reorders what the reader sees, nothing that rides invisibly inside it, and — where
 * the plane is the log — nothing that opens a record of its own. Shared by the sink converter specs
 * and the end-to-end invariant spec so the three ask the same question of their own subjects.
 *
 * <p>The class list is stated here independently rather than read from the production
 * {@code CharacterTable}, which is package-private in {@code :logtext} and would in any case make
 * the assertion circular — an oracle that asks the subject what "inert" means cannot catch the
 * subject keeping the wrong thing. {@code InertTextSpec} is what keeps this list from falling
 * behind the table: it asserts one class per row of it.
 *
 * <p>FR1, FR3, FR4, NFR-S1 of harden-untrusted-text-sinks.
 */
final class InertText {

    /**
     * Everything the log plane must not contain: the C0 controls except {@code \n} and {@code \t}
     * (ESC among them), DEL and the C1 range, the bidi overrides and isolates, the widthless
     * format characters, and the astral tag block.
     */
    static boolean isInert(String text) {
        text.codePoints().noneMatch { int c ->
            c != 0x0A && c != 0x09 && (
            c < 0x20 ||
            (c >= 0x7F && c <= 0x9F) ||
            (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069) ||
            (c >= 0x200B && c <= 0x200F) || (c >= 0x2060 && c <= 0x2064) || c == 0xFEFF ||
            (c >= 0xE0000 && c <= 0xE007F)
            )
        }
    }

    /** No separator any reader treats as a line break — the one-event-one-line half. */
    static boolean isSingleLine(String text) {
        !(text =~ /\R/)
    }

    private InertText() {}
}
