package com.github.oinsio.gnomish.untrustedtext

import spock.lang.Specification

/**
 * FR1, FR4, design D1 of type-untrusted-text: the carrier every piece of text entering the
 * factory from outside its trust boundary travels in. Seven mints, one per capture family, each
 * tagging the text with the provenance that travels with it; one raw accessor reserved for exit
 * owners; and — the reason it is a final class rather than a record — a {@code toString()} that
 * is the log exit, so the classic laundering move of concatenating untrusted text into a
 * factory-authored string yields neutralized text instead of a hole.
 */
class UntrustedTextSpec extends Specification {

    private static final String TEXT = 'fatal: not a git repository'

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
        def carrier = UntrustedText.subprocess('x' * 500 + 'THE-ERROR')

        when:
        def excerpt = carrier.excerpt(20)

        then: 'the tail survives — that is where the error is — and the volume is bounded'
        excerpt.endsWith('THE-ERROR')
        excerpt == TextSafety.forLog(carrier.raw(), 20)
        carrier.forLog().length() > excerpt.length()
    }

    def "an excerpt refuses a non-positive bound"() {
        when:
        UntrustedText.subprocess(TEXT).excerpt(0)

        then:
        thrown(IllegalArgumentException)
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
        'subprocess' | { UntrustedText.subprocess(it) }
        'container' | { UntrustedText.container(it) }
        'agent' | { UntrustedText.agent(it) }
        'tracker' | { UntrustedText.tracker(it) }
        'manifest' | { UntrustedText.manifest(it) }
        'branch document' | { UntrustedText.branchDocument(it) }
    }
}
