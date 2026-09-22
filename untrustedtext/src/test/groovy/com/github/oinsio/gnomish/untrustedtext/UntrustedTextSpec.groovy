package com.github.oinsio.gnomish.untrustedtext

import spock.lang.Specification

/**
 * FR1, FR4, design D1 of type-untrusted-text: the carrier every piece of text entering the
 * factory from outside its trust boundary travels in. Seven mints, one per capture family, plus
 * the factory's own for the sentences it composes around them, each tagging the text with the
 * provenance that travels with it; one raw accessor reserved for exit
 * owners; and — the reason it is a final class rather than a record — a {@code toString()} that
 * is the log exit, so the classic laundering move of concatenating untrusted text into a
 * factory-authored string yields neutralized text instead of a hole.
 */
class UntrustedTextSpec extends Specification {

    private static final String TEXT = 'fatal: not a git repository'

    /**
     * {@code U+2028} and {@code U+2029} as numeric constants: both are invisible in a source file,
     * and a backslash-u escape is expanded by the lexer before parsing, which would put a real line
     * break in this file.
     */
    private static final String LINE_SEPARATOR = Character.toString(0x2028)

    private static final String PARAGRAPH_SEPARATOR = Character.toString(0x2029)

    /** The bound the excerpt scenario below asks for, and the bound GitCommandResult quotes under. */
    private static final int CAP = 1_400

    def "each capture family has its own mint, and the provenance travels with the text — #family"() {
        expect:
        carrier.raw() == TEXT
        carrier.provenance() == provenance

        where:
        family | carrier || provenance
        'subprocess' | UntrustedText.subprocess(TEXT) || Provenance.SUBPROCESS
        'container' | UntrustedText.container(TEXT) || Provenance.CONTAINER
        'agent' | UntrustedText.agent(TEXT) || Provenance.AGENT
        'tracker' | UntrustedText.tracker(TEXT) || Provenance.TRACKER
        'manifest' | UntrustedText.manifest(TEXT) || Provenance.MANIFEST
        'branch document' | UntrustedText.branchDocument(TEXT) || Provenance.BRANCH_DOCUMENT
        'operator' | UntrustedText.operator(TEXT) || Provenance.OPERATOR
        'factory' | UntrustedText.factory(TEXT) || Provenance.FACTORY
    }

    // FR4, design D3: the factory's own sentences are a carrier because the field is one, and the
    //     family says so — the alternative files factory prose under a source that did not write it.
    def "a sentence the factory composed is minted in its own family, not in the one it quotes"() {
        given: 'captured output, and the sentence a report builds around it'
        def captured = UntrustedText.subprocess(TEXT)
        def sentence = UntrustedText.factory("the fetch of 'main' was refused: " + captured.forLog())

        expect: 'the quote is named by the family it was captured in'
        captured.provenance() == Provenance.SUBPROCESS

        and: 'and the sentence built around it by the one that wrote it'
        sentence.provenance() == Provenance.FACTORY

        and: 'the quote is inert before it enters the sentence, so the sentence carries no raw byte'
        sentence.raw().contains(captured.forLog())

        and: 'and the sentence still renders through the same exits as any other carrier'
        sentence.toString() == sentence.forLog()
    }

    def "every provenance names itself in words a report can print — #provenance"() {
        expect: 'a report three calls away needs no other source to say where the text came from'
        !provenance.description().isBlank()

        and: 'and the subprocess family says exactly that'
        Provenance.SUBPROCESS.description() == 'subprocess output'

        where:
        provenance << Provenance.values()
    }

    def "two carriers are the same value when the raw text is"() {
        given:
        def one = UntrustedText.subprocess(TEXT)
        def same = UntrustedText.subprocess(TEXT)

        expect:
        one == same
        one.hashCode() == same.hashCode()
    }

    // D1 (revised 2026-09-17), FR4: the round trip this change owes — a cause captured from a
    //     subprocess, written to task.json and read back as a branch document — is the same value.
    def "the same text under two provenances is one value, so a document round trip preserves it"() {
        given: 'the carrier a writer held, and the one its reader minted off the branch'
        def written = UntrustedText.subprocess(TEXT)
        def readBack = UntrustedText.branchDocument(TEXT)

        expect: 'provenance is evidence a report may name, never part of the value'
        written == readBack
        written.hashCode() == readBack.hashCode()

        and: 'so a set of carriers over two media deduplicates, rather than holding both'
        ([written, readBack] as Set).size() == 1

        and: 'and each still names where it came from'
        written.provenance() == Provenance.SUBPROCESS
        readBack.provenance() == Provenance.BRANCH_DOCUMENT
    }

    def "carriers differ when the text differs"() {
        expect:
        UntrustedText.subprocess(TEXT) != UntrustedText.subprocess('other')

        and: 'and they hash apart, so a set of carriers is not a set of one'
        UntrustedText.subprocess(TEXT).hashCode() != UntrustedText.subprocess('other').hashCode()

        and:
        UntrustedText.subprocess(TEXT) != (TEXT as Object)
    }

    def "the default rendering is the log exit, byte for byte — #label"() {
        given:
        def carrier = UntrustedText.subprocess(input)

        expect: 'a record\'s toString() would have printed the raw text; this one cannot'
        carrier.toString() == carrier.forLog()

        and: 'so concatenation into a factory-authored message is inert'
        "git said: ${carrier}" == 'git said: ' + carrier.forLog()

        where:
        label << AdversarialCorpus.ENTRIES.keySet()
        input << AdversarialCorpus.ENTRIES.values()
    }

    def "an excerpt is the log exit under a caller's own bound"() {
        given: 'more text than the excerpt asks for'
        def carrier = UntrustedText.subprocess('x' * 5_000 + 'THE-ERROR')

        when:
        def excerpt = carrier.excerpt(200)

        then: 'the tail survives — that is where the error is — and the volume is bounded'
        excerpt.endsWith('THE-ERROR')
        excerpt.length() <= 200
        carrier.forLog().length() > excerpt.length()

        and: 'text already inside the bound is the log exit under that bound, untouched'
        UntrustedText.subprocess(TEXT).excerpt(200) == TextSafety.forLog(TEXT, 200)
    }

    // FR10 of fix-envelope-medium (design D9): the caller's bound is a statement about what
    //     leaves the exit, not about what enters the flattening. A capture of nothing but line
    //     separators renders six characters per one, so an input-only bound handed the caller an
    //     excerpt six times the size it asked for — and every prose bound built on top of it,
    //     GitCommandResult.STDERR_CAP_CHARS first among them, lost its headroom with it.
    def "FR10: an excerpt never exceeds its bound after rendering — #label"() {
        given: 'the rendered text the exit would produce with no outer bound'
        def flattened = TextSafety.flatten(TextSafety.strip(raw))

        when:
        def excerpt = UntrustedText.subprocess(raw).excerpt(CAP)

        then: 'the bound holds on what leaves, not on what entered'
        excerpt.length() <= CAP

        and: 'one event is still one line — the outer cut is flattened like the rest'
        !excerpt.contains('\n')
        !excerpt.contains('\r')

        and: 'the tail, where the error is, survives'
        excerpt.endsWith(flattened[-50..-1])

        and: 'a cut names itself, and text within the bound is left whole'
        (flattened.length() > CAP) == excerpt.contains('[truncated')

        where:
        label | raw
        '1 400 line separators' | LINE_SEPARATOR * 1_400
        '1 400 paragraph separators' | PARAGRAPH_SEPARATOR * 1_400
        'mixed separators and prose' | ('fatal: bad object' + LINE_SEPARATOR + '\n\t') * 60
        'plain ASCII within the bound' | 'x' * 900 + 'THE-ERROR'
    }

    // FR10, design D9: the floor itself is a bound the excerpt honours, marker included — the
    //     smallest cap a caller may ask for is one the method can still keep its promise under.
    def "an excerpt honours the smallest bound it accepts"() {
        given:
        def carrier = UntrustedText.subprocess('x' * 5_000 + 'THE-ERROR')

        when:
        def excerpt = carrier.excerpt(UntrustedText.MIN_EXCERPT_CAP_CHARS)

        then:
        excerpt.length() <= UntrustedText.MIN_EXCERPT_CAP_CHARS
        excerpt.endsWith('THE-ERROR')
        excerpt.contains('[truncated')
    }

    // FR10, design D9: the bound is on what leaves, so it must be wide enough for the truncation
    //     marker that names the cut — below that the method could not keep its own promise.
    def "an excerpt refuses a bound its own truncation marker would not fit — #cap"() {
        when:
        UntrustedText.subprocess(TEXT).excerpt(cap)

        then:
        thrown(IllegalArgumentException)

        where:
        cap << [
            0,
            -1,
            UntrustedText.MIN_EXCERPT_CAP_CHARS - 1
        ]
    }

    def "FR10: the parsing exit hands back the captured bytes — #label"() {
        given: 'text a parser must see as it arrived, not as a reader would be shown it'
        def carrier = UntrustedText.subprocess(input)

        expect: 'an exit would cap and flatten it, and the parse would read the wrong answer'
        carrier.forParsing() == carrier.raw()
        carrier.forParsing() == input

        where:
        label << AdversarialCorpus.ENTRIES.keySet()
        input << AdversarialCorpus.ENTRIES.values()
    }

    def "FR10: a question about the text is answered without rendering it"() {
        given: 'output whose only interesting property is a marker in it'
        def carrier = UntrustedText.subprocess("before${AdversarialCorpus.ESC}[2Jalready exists")

        expect: 'emptiness, substring and length answer — a boolean and an int carry no text out'
        !carrier.isBlank()
        UntrustedText.subprocess(' \n\t').isBlank()
        carrier.contains('already exists')
        !carrier.contains('no such container')
        carrier.length() == carrier.raw().length()
    }

    def "FR10: a substring question refuses a null needle"() {
        when:
        UntrustedText.subprocess(TEXT).contains(null)

        then:
        thrown(NullPointerException)
    }

    def "no mint accepts null text — #family"() {
        when:
        mint.call(null)

        then:
        thrown(NullPointerException)

        where:
        family | mint
        'subprocess' | { String it -> UntrustedText.subprocess(it) }
        'container' | { String it -> UntrustedText.container(it) }
        'agent' | { String it -> UntrustedText.agent(it) }
        'tracker' | { String it -> UntrustedText.tracker(it) }
        'manifest' | { String it -> UntrustedText.manifest(it) }
        'branch document' | { String it -> UntrustedText.branchDocument(it) }
        'operator' | { String it -> UntrustedText.operator(it) }
        'factory' | { String it -> UntrustedText.factory(it) }
    }
}
