package com.github.oinsio.gnomish.app.port.tracker

import java.time.Instant
import spock.lang.Specification

/**
 * AbortRecord: the write-side payload for {@code recordAbort} — a free-text
 * cause, the aborting instance's identifier, and when it happened (design D1
 * sketch, FR14). Implements FR14 of add-tracker-port.
 */
class AbortRecordSpec extends Specification {

    // FR14: cause, instance and at round-trip exactly as constructed
    def "exposes cause, instance and at exactly as constructed"() {
        given:
        def at = Instant.parse('2026-07-20T10:00:00Z')

        when:
        def record = new AbortRecord('build failed', 'instance-a', at)

        then: 'each component is exposed exactly as constructed, not an empty stand-in'
        record.cause() == 'build failed'
        record.instance() == 'instance-a'
        record.at() == at
    }

    // FR14: an abort marker with no explanation or no attributable instance cannot be
    //     reconstructed usefully by another instance
    def "blank #component is rejected with the component name in the message"() {
        when:
        new AbortRecord(cause, instance, Instant.parse('2026-07-20T10:00:00Z'))

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("AbortRecord.$component")

        where:
        cause | instance | component
        '' | 'instance-a' | 'cause'
        '   ' | 'instance-a' | 'cause'
        'build failed' | '' | 'instance'
        'build failed' | '\t' | 'instance'
    }

    // FR14: abort records are values — equal content means equal records
    def "records with the same components are equal values"() {
        given:
        def at = Instant.parse('2026-07-20T10:00:00Z')

        expect:
        new AbortRecord('build failed', 'instance-a', at) == new AbortRecord('build failed', 'instance-a', at)
    }
}
