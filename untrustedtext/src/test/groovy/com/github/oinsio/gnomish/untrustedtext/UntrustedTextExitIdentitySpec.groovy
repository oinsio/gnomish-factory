package com.github.oinsio.gnomish.untrustedtext

import spock.lang.Specification

/**
 * FR2 of type-untrusted-text: the carrier's three exits are not a second implementation of
 * anything. Each computes exactly what this module's own primitive computes for the same raw
 * text — for every entry of the adversarial corpus and every provenance, since provenance is
 * evidence and must change no rendering — and the default rendering equals the log exit.
 *
 * <p>This is the identity the design's single-owner table names: without it, "the carrier renders
 * through the owner" is a claim about code that reads right rather than a property of the build.
 */
class UntrustedTextExitIdentitySpec extends Specification {

    /** The corpus × every provenance: 'provenance is evidence, not policy' has to be asserted, not assumed. */
    static List<List<Object>> cases() {
        [
            AdversarialCorpus.ENTRIES.keySet().toList(),
            Provenance.values().toList()
        ].combinations()
    }

    static UntrustedText mint(String text, Provenance provenance) {
        return switch (provenance) {
                    case Provenance.SUBPROCESS -> UntrustedText.subprocess(text)
                    case Provenance.CONTAINER -> UntrustedText.container(text)
                    case Provenance.AGENT -> UntrustedText.agent(text)
                    case Provenance.TRACKER -> UntrustedText.tracker(text)
                    case Provenance.MANIFEST -> UntrustedText.manifest(text)
                    case Provenance.BRANCH_DOCUMENT -> UntrustedText.branchDocument(text)
                    case Provenance.OPERATOR -> UntrustedText.operator(text)
                }
    }

    def "the log exit is the owner's log composition — #label as #provenance"() {
        given:
        def input = AdversarialCorpus.ENTRIES[label]

        expect:
        mint(input, provenance as Provenance).forLog() == TextSafety.forLog(input, TextSafety.DEFAULT_CAP_CHARS)

        where:
        [label, provenance] << cases()
    }

    def "the console exit is the owner's console notation — #label as #provenance"() {
        given:
        def input = AdversarialCorpus.ENTRIES[label]

        expect:
        mint(input, provenance as Provenance).forConsole() == TextSafety.forConsole(input)

        where:
        [label, provenance] << cases()
    }

    def "the comment exit is the owner's fenced rendering — #label as #provenance"() {
        given:
        def input = AdversarialCorpus.ENTRIES[label]

        expect:
        mint(input, provenance as Provenance).forComment() == TextSafety.forComment(input)

        where:
        [label, provenance] << cases()
    }

    def "the default rendering equals the log exit — #label as #provenance"() {
        given:
        def input = AdversarialCorpus.ENTRIES[label]

        expect:
        mint(input, provenance as Provenance).toString() == TextSafety.forLog(input, TextSafety.DEFAULT_CAP_CHARS)

        where:
        [label, provenance] << cases()
    }
}
