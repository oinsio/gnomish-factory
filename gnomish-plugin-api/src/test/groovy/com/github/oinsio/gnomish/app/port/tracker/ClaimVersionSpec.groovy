package com.github.oinsio.gnomish.app.port.tracker

import java.time.Instant
import spock.lang.Specification

/**
 * ClaimVersion: the opaque lease version — marker identity plus last-update fact
 * — that core compares by content to detect staleness (design D2/D5). Implements
 * FR5 of add-claim-heartbeat.
 */
class ClaimVersionSpec extends Specification {

    // FR5: a claim version exposes its marker identity and last-update fact exactly as constructed
    def "exposes markerId and updatedAt exactly as constructed"() {
        given:
        def updatedAt = Instant.parse('2026-07-29T10:15:30Z')

        when:
        def version = new ClaimVersion('claim-comment-991', updatedAt)

        then:
        version.markerId() == 'claim-comment-991'
        version.updatedAt() == updatedAt
    }

    // FR5: a version with no marker identity cannot anchor a lease
    def "rejects a blank markerId with the component named"() {
        when:
        new ClaimVersion(markerId, Instant.parse('2026-07-29T10:15:30Z'))

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('ClaimVersion.markerId')

        where:
        markerId << ['', '   ', '\t', ' \n']
    }

    // FR5, D2: staleness is decided by content equality — same facts means same version
    def "versions with the same components are equal values"() {
        given:
        def at = Instant.parse('2026-07-29T10:15:30Z')
        def other = Instant.parse('2026-07-29T10:20:30Z')

        expect:
        new ClaimVersion('m1', at) == new ClaimVersion('m1', at)

        and: 'a differing marker identity makes them unequal'
        new ClaimVersion('m1', at) != new ClaimVersion('m2', at)

        and: 'a differing last-update fact makes them unequal — the beat signal'
        new ClaimVersion('m1', at) != new ClaimVersion('m1', other)
    }
}
