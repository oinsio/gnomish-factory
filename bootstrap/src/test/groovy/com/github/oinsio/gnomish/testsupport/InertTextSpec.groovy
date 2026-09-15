package com.github.oinsio.gnomish.testsupport

import spock.lang.Specification

/**
 * The oracle every sink spec reads is itself asserted here: a helper that answers "inert" to a
 * character class the log plane removes would let those specs pass over output that still carries
 * it, and nothing would be red. One feature per class the {@code CharacterTable} names, driven off
 * the same corpus the sink specs use.
 *
 * <p>FR1, FR3, FR4, NFR-S1 of harden-untrusted-text-sinks.
 */
class InertTextSpec extends Specification {

    def "a character the log plane removes is not inert: #shape"() {
        expect:
        !InertText.isInert(text)

        where:
        shape | text
        'NUL' | AdversarialCorpus.ch(0x00)
        'backspace' | AdversarialCorpus.ch(0x08)
        'CR' | AdversarialCorpus.ch(0x0D)
        'C0 upper edge (US)' | AdversarialCorpus.ch(0x1F)
        'ESC' | AdversarialCorpus.ch(0x1B)
        'DEL' | AdversarialCorpus.ch(0x7F)
        'C1 lower edge' | AdversarialCorpus.ch(0x80)
        'C1 upper edge' | AdversarialCorpus.ch(0x9F)
        'bidi LRE' | AdversarialCorpus.ch(0x202A)
        'bidi RLO' | AdversarialCorpus.ch(0x202E)
        'bidi isolate LRI' | AdversarialCorpus.ch(0x2066)
        'bidi isolate PDI' | AdversarialCorpus.ch(0x2069)
        'zero-width space' | AdversarialCorpus.ch(0x200B)
        'right-to-left mark' | AdversarialCorpus.ch(0x200F)
        'word joiner' | AdversarialCorpus.ch(0x2060)
        'invisible plus' | AdversarialCorpus.ch(0x2064)
        'BOM' | AdversarialCorpus.ch(0xFEFF)
        'tag range lower edge' | AdversarialCorpus.ch(0xE0000)
        'tag range upper edge' | AdversarialCorpus.ch(0xE007F)
    }

    def "the two characters the table keeps stay inert: #shape"() {
        expect:
        InertText.isInert(text)

        where:
        shape | text
        'newline' | 'a\nb'
        'tab' | 'a\tb'
        'plain' | 'fatal: not a git repository'
        'astral' | AdversarialCorpus.ch(0x1F600)
        'below the bidi block' | AdversarialCorpus.ch(0x2029)
    }

    def "a line separator is not single-line: #shape"() {
        expect:
        !InertText.isSingleLine(text)

        where:
        shape | text
        'LF' | 'a\nb'
        'CR' | "a${AdversarialCorpus.ch(0x0D)}b"
        'U+2028'| "a${AdversarialCorpus.ch(0x2028)}b"
    }
}
