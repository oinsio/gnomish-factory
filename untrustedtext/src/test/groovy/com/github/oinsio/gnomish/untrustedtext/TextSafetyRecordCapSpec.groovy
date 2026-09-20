package com.github.oinsio.gnomish.untrustedtext

import spock.lang.Specification

/**
 * {@link TextSafety#capRecord}: the bound the log sink puts on one rendered record component — a
 * formatted message, an MDC value, a whole rendered throwable — as opposed to
 * {@link TextSafety#capTail}'s bound on one untrusted excerpt. The two caps answer different
 * questions and therefore keep different ends of the text: an excerpt is capped to its tail,
 * because the error is at the end of command output; a component is capped to its head, because
 * the operator-event code — and, for a throwable, the top-level message and throw site — is at the
 * start and is what makes the record findable at all.
 *
 * <p>FR1, FR2 of harden-untrusted-text-sinks (design D2).
 */
class TextSafetyRecordCapSpec extends Specification {

    def "FR1: a record within the cap is returned verbatim"() {
        expect: 'the cap is a bound, not a rewrite'
        TextSafety.capRecord(input) == input

        where:
        input << [
            '',
            'one ordinary log record',
            'x' * (TextSafety.RECORD_CAP_CHARS - 1)
        ]
    }

    def "FR1: a record of exactly the cap is returned verbatim"() {
        given:
        def atCap = 'x' * TextSafety.RECORD_CAP_CHARS

        expect: 'the threshold is the last length that passes, not the first that truncates'
        TextSafety.capRecord(atCap) == atCap
    }

    def "FR1: an over-cap record keeps its head and names what was dropped"() {
        given:
        def flood = 'HEAD-' + 'x' * (TextSafety.RECORD_CAP_CHARS * 4)

        when:
        def capped = TextSafety.capRecord(flood)

        then: 'the head — where the event code and a throwable top-level message live — survives'
        capped.startsWith('HEAD-')

        and: 'the marker is visible and names the drop in the record itself'
        capped ==~ /^HEAD-x+ \[record truncated, dropped \d+ of \d+ chars]$/

        and: 'the head is exactly the cap less the marker reserve, not merely something short enough'
        def headChars = TextSafety.RECORD_CAP_CHARS - TextSafety.TRUNCATION_MARKER_RESERVE
        capped.indexOf(' [record truncated') == headChars

        and: 'the counts are the truth about this record'
        def match = capped =~ /dropped (\d+) of (\d+) chars]$/
        match.find()
        match.group(1) as int == flood.length() - headChars
        match.group(2) as int == flood.length()

        and: 'the result is within the bound it exists to enforce'
        capped.length() <= TextSafety.RECORD_CAP_CHARS
    }

    def "FR2: the cap is idempotent over its own output"() {
        given:
        def capped = TextSafety.capRecord('x' * (TextSafety.RECORD_CAP_CHARS * 3))

        expect: 'a second pass finds nothing left to do — no marker stacked on a marker'
        TextSafety.capRecord(capped) == capped
        capped.count('[record truncated') == 1
    }

    def "NFR-S1: the head never ends on half an astral character"() {
        given: 'a record whose head boundary falls on the HIGH half of a surrogate pair'
        def grinning = new String(Character.toChars(0x1F600))
        def prefix = 'x' * (TextSafety.RECORD_CAP_CHARS - TextSafety.TRUNCATION_MARKER_RESERVE - 1)
        def flood = prefix + grinning + 'y' * TextSafety.RECORD_CAP_CHARS

        expect: 'the boundary really is mid-pair, or the scenario proves nothing'
        Character.isHighSurrogate(flood.charAt(prefix.length()))

        when:
        def capped = TextSafety.capRecord(flood)

        then: 'the orphaned half is dropped rather than emitted as a lone surrogate'
        capped.codePoints().noneMatch { Character.isSurrogate((char) it) }
        capped.startsWith(prefix + ' [record truncated')
    }

    def "NFR-S1: a pair wholly inside the head is kept whole"() {
        given: 'a record whose head boundary lands past the pair, not on it'
        def grinning = new String(Character.toChars(0x1F600))
        def prefix = 'x' * (TextSafety.RECORD_CAP_CHARS - TextSafety.TRUNCATION_MARKER_RESERVE - 4)
        def flood = prefix + grinning + 'y' * TextSafety.RECORD_CAP_CHARS

        expect: 'the boundary is an ordinary character'
        !Character.isSurrogate(flood.charAt(TextSafety.RECORD_CAP_CHARS - TextSafety.TRUNCATION_MARKER_RESERVE - 1))

        when:
        def capped = TextSafety.capRecord(flood)

        then:
        capped.contains(grinning)
    }

    def "the marker always fits the budget reserved for it inside the cap"() {
        given: 'the widest marker the formatter can produce — both counts at their maximum width'
        def widest = ' [record truncated, dropped %d of %d chars]'.formatted(Integer.MAX_VALUE, Integer.MAX_VALUE)

        expect: 'the reserve is what makes the output fit the cap; it must cover the worst case'
        widest.length() <= TextSafety.TRUNCATION_MARKER_RESERVE
    }

    def "the cap is the value design D2 chose, stated once"() {
        expect: '16 KB — two orders above the longest legitimate summary, above the widest forLog output'
        TextSafety.RECORD_CAP_CHARS == 16_384
    }

    def "FR2: choke-point output never reaches the record cap"() {
        given: 'the widest thing forLog can emit: a full cap of the widest escape it writes'
        def widest = AdversarialCorpus.forLog(new String(Character.toChars(0x2029)) * (TextSafety.DEFAULT_CAP_CHARS * 10))

        expect: 'the record cap sits above it by construction, so a prepared message is untouched'
        widest.length() <TextSafety.RECORD_CAP_CHARS
        TextSafety.capRecord(widest) == widest
    }
}
