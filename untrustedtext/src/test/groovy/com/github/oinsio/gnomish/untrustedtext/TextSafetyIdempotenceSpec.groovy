package com.github.oinsio.gnomish.untrustedtext

import spock.lang.Specification

/**
 * FR2 of harden-untrusted-text-sinks (design D2): the sink layer this change adds must be invisible
 * to a call site that did the right thing. The sink applies the same three primitives to whatever
 * reaches it, so the property that makes it invisible is idempotence — a message the choke point
 * already prepared must survive the sink byte for byte, with no second cap and, above all, no
 * second escaping of the visible {@code \\n} marker the first pass wrote.
 *
 * <p>Idempotence here is by construction, not by a marker the sink looks for: a stripped text has
 * nothing left to strip, a flattened text has no separator left to flatten (the literal
 * backslash-n it carries is plain text), and {@link TextSafety#RECORD_CAP_CHARS} sits above anything
 * {@code forLog} can emit. This spec is what turns that argument into evidence, over every
 * class the table claims.
 */
class TextSafetyIdempotenceSpec extends Specification {

    def "FR2: choke-point output survives the sink's own primitives unchanged — #label"() {
        given: 'the message a correct call site hands the logger'
        def prepared = AdversarialCorpus.forLog(raw)

        expect: 'the sink pass leaves it byte-identical'
        TextSafety.strip(TextSafety.flatten(TextSafety.capRecord(prepared))) == prepared

        where:
        label << AdversarialCorpus.ENTRIES.keySet()
        raw << AdversarialCorpus.ENTRIES.values()
    }

    def "FR2: the order the sink applies them in does not matter for prepared text — #label"() {
        given:
        def prepared = AdversarialCorpus.forLog(raw)

        expect: 'design D2 states strip-then-flatten-then-cap; neither reading can change the bytes'
        TextSafety.capRecord(TextSafety.flatten(TextSafety.strip(prepared))) == prepared

        where:
        label << AdversarialCorpus.ENTRIES.keySet()
        raw << AdversarialCorpus.ENTRIES.values()
    }

    def "FR2: no second escaping of the visible newline marker"() {
        given: 'text whose break the choke point already rendered as the two characters backslash-n'
        def prepared = AdversarialCorpus.forLog('stage failed\nsecond line')

        expect:
        prepared == 'stage failed\\nsecond line'
        TextSafety.flatten(prepared) == prepared
    }

    def "FR2: no second cap below the first — the record bound is never reached"() {
        expect: 'whatever the input, forLog output is far inside the record cap'
        AdversarialCorpus.forLog(raw).length() <TextSafety.RECORD_CAP_CHARS

        where:
        raw << AdversarialCorpus.ENTRIES.values()
    }

    def "NFR-S1: every corpus entry leaves the choke point inert and single-line — #label"() {
        given:
        def prepared = AdversarialCorpus.forLog(raw)

        expect: 'one line'
        !prepared.contains('\n')
        !prepared.contains('\r')

        and: 'nothing the table names survives'
        prepared.codePoints().noneMatch { neutralized(it) }

        where:
        label << AdversarialCorpus.ENTRIES.keySet()
        raw << AdversarialCorpus.ENTRIES.values()
    }

    /**
     * The claim restated as data rather than reused from {@link CharacterTable}, shared with
     * {@link TextSafetyConsoleSpec} via {@link HostileCodePoints}: the log plane neutralizes the line
     * feed too, so this predicate takes {@link HostileCodePoints#inTable} unmodified.
     */
    private static boolean neutralized(int codePoint) {
        HostileCodePoints.inTable(codePoint)
    }
}
