package com.github.oinsio.gnomish.testsupport

/**
 * The corpus the sink-side specs of this module are driven over: one entry per character class the
 * log text table claims to neutralize, plus the line-forgery and volume shapes. The converter
 * specs read it for their component-level claims and the end-to-end invariant spec reads it for
 * the byte-level one, so a new claim joins both at once.
 *
 * <p>Written as code points, never as literal characters: a NUL, a zero-width space or a tag
 * character pasted into a source file is invisible to a reviewer and lost by the next tool that
 * touches the file.
 *
 * <p>The twin of {@code :logtext}'s own corpus of the same name — deliberately a copy rather than
 * a shared fixture, because {@code :logtext} reaches no other project by the gate its build file
 * carries, and this module's question is about the rendered record rather than the primitive.
 *
 * <p>FR1, FR3, FR4, NFR-S1 of harden-untrusted-text-sinks.
 */
final class AdversarialCorpus {

    static String ch(int codePoint) {
        new String(Character.toChars(codePoint))
    }

    static final String ESC = ch(0x1B)

    static final Map<String, String> ENTRIES = [
        'plain text': 'fatal: not a git repository',
        'CR': "a${ch(0x0D)}b",
        'CRLF forged record': "stage failed${ch(0x0D)}\n2026-08-31 12:00:00 ERROR [main] compromised",
        'tab': 'a\tb',
        'U+2028 line separator': "a${ch(0x2028)}b",
        'U+2029 paragraph separator': "a${ch(0x2029)}b",
        'ANSI CSI colour': "${ESC}[31mred${ESC}[0m",
        'ANSI CSI cursor': "before${ESC}[2Jafter",
        'OSC 52 clipboard write': "${ESC}]52;c;cGF5bG9hZA==${ch(0x07)}title",
        'OSC without a terminator': "${ESC}]0;runs to the end",
        'DCS with ST': "a${ESC}Pq-payload${ESC}\\b",
        'SOS without a terminator': "a${ESC}Xpayload runs on",
        'PM with BEL': "a${ESC}^payload${ch(0x07)}b",
        'APC with ST': "a${ESC}_payload${ESC}\\b",
        'bare Fe escape': "a${ESC}Db",
        'lone ESC': "a${ESC}b",
        'NUL': "a${ch(0x00)}b",
        'backspace': "a${ch(0x08)}b",
        'DEL': "a${ch(0x7F)}b",
        'C1 lower edge': "a${ch(0x80)}b",
        'C1 CSI': "a${ch(0x9B)}31mb",
        'C1 upper edge': "a${ch(0x9F)}b",
        'bidi RLO': "a${ch(0x202E)}b",
        'bidi isolate PDI': "a${ch(0x2069)}b",
        'zero-width space': "a${ch(0x200B)}b",
        'right-to-left mark': "a${ch(0x200F)}b",
        'word joiner': "a${ch(0x2060)}b",
        'zero-width no-break space (BOM)': "a${ch(0xFEFF)}b",
        'tag range lower edge': "a${ch(0xE0000)}b",
        'tag range upper edge': "a${ch(0xE007F)}b",
        'forged log record': 'stage failed\n2026-08-31 12:00:00 ERROR [main] compromised',
        'a megabyte of payload': 'x' * 1_000_000 + 'THE-ERROR',
    ]

    private AdversarialCorpus() {}
}
