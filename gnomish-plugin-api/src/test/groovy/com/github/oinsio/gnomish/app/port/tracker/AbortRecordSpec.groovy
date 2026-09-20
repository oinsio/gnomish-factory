package com.github.oinsio.gnomish.app.port.tracker

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * AbortRecord: the write-side payload for {@code recordAbort} — the carried
 * cause, the aborting instance's identifier, and when it happened (design D1
 * sketch, FR14). Implements FR14 of add-tracker-port; the cause is
 * {@link UntrustedText} by design D4 of type-untrusted-text, so the adapter that
 * publishes the marker is the one that renders it.
 */
class AbortRecordSpec extends Specification {

    // FR14: cause, instance and at round-trip exactly as constructed
    def "exposes cause, instance and at exactly as constructed"() {
        given:
        def at = Instant.parse('2026-07-20T10:00:00Z')

        when:
        def record = new AbortRecord(cause('build failed'), 'instance-a', at)

        then: 'each component is exposed exactly as constructed, not an empty stand-in'
        record.cause() == cause('build failed')
        record.instance() == 'instance-a'
        record.at() == at
    }

    // FR14 of harden-task-branch-contract: the category places the attempt in the unified
    // accounting, and a record written without one is the category every such marker meant
    def "carries the recovery category, defaulting to an instance crash"() {
        given:
        def at = Instant.parse('2026-07-20T10:00:00Z')

        expect: 'an explicitly categorized record keeps its category'
        new AbortRecord(cause('repair failed'), 'instance-a', at, RecoveryCause.RECOVERY_FAILURE).category() ==
                RecoveryCause.RECOVERY_FAILURE

        and: 'one written without a category reads as a crashed run'
        new AbortRecord(cause('build failed'), 'instance-a', at).category() == RecoveryCause.INSTANCE_CRASH
    }

    // FR14: an abort marker with no explanation or no attributable instance cannot be
    //     reconstructed usefully by another instance
    def "blank #component is rejected with the component name in the message"() {
        when:
        new AbortRecord(cause(causeText), instance, Instant.parse('2026-07-20T10:00:00Z'))

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("AbortRecord.$component")

        where:
        causeText | instance | component
        '' | 'instance-a' | 'cause'
        '   ' | 'instance-a' | 'cause'
        'build failed' | '' | 'instance'
        'build failed' | '\t' | 'instance'
    }

    // D4 of type-untrusted-text: the marker's cause is whatever a subprocess, an agent or a
    //     container said, so it crosses the published contract carried rather than rendered.
    def "the cause crosses the contract as a carrier, so the adapter chooses the rendering"() {
        given:
        def esc = Character.toString(27 as char)
        def hostile = UntrustedText.subprocess('build failed' + esc + '[2J')

        when:
        def record = new AbortRecord(hostile, 'instance-a', Instant.parse('2026-07-20T10:00:00Z'))

        then: 'the carrier arrives whole — nothing was rendered on the way in'
        record.cause() == hostile

        and: 'and every exit is still available to whoever writes it'
        !record.cause().forComment().contains(esc)
    }

    // FR14: abort records are values — equal content means equal records
    def "records with the same components are equal values"() {
        given:
        def at = Instant.parse('2026-07-20T10:00:00Z')

        expect:
        new AbortRecord(cause('build failed'), 'instance-a', at) ==
                new AbortRecord(cause('build failed'), 'instance-a', at)
    }

    /** A cause as the abort handler mints it: whatever the failed run said. */
    private static UntrustedText cause(String text) {
        UntrustedText.subprocess(text)
    }
}
