package com.github.oinsio.gnomish.testsupport

/**
 * What "neutralized" means when a spec asserts it on rendered text: nothing a terminal executes,
 * nothing that reorders what the reader sees, and — where the plane is the log — nothing that
 * opens a record of its own. Shared by the sink converter specs and the end-to-end invariant spec
 * so the three ask the same question of their own subjects.
 *
 * <p>FR1, FR3, FR4, NFR-S1 of harden-untrusted-text-sinks.
 */
final class InertText {

    /** Everything the log plane must not contain: ESC, the C1 range, DEL, the bidi controls. */
    static boolean isInert(String text) {
        text.codePoints().noneMatch { int c ->
            c == 0x1B || (c >= 0x7F && c <= 0x9F) ||
            (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)
        }
    }

    /** No separator any reader treats as a line break — the one-event-one-line half. */
    static boolean isSingleLine(String text) {
        !(text =~ /\R/)
    }

    private InertText() {}
}
