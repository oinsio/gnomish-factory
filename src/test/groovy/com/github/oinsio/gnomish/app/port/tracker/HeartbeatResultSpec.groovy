package com.github.oinsio.gnomish.app.port.tracker

import java.time.Instant
import spock.lang.Specification

/**
 * HeartbeatResult: the outcome of a heartbeat write — Beaten(newVersion), the
 * refreshed lease, or ClaimGone, the protocol signal that the claim was removed
 * or taken over (design D1/D7). Infrastructure failures stay exceptions, not a
 * result. Implements FR5, FR8 of add-claim-heartbeat.
 */
class HeartbeatResultSpec extends Specification {

    // FR5: a successful beat carries the refreshed claim version
    def "Beaten exposes the refreshed claim version exactly as constructed"() {
        given:
        def version = new ClaimVersion('claim-comment-991', Instant.parse('2026-07-29T10:15:30Z'))

        expect:
        new HeartbeatResult.Beaten(version).version() == version
    }

    // FR8: a gone claim is a distinct protocol signal, not an infrastructure error
    def "an exhaustive switch over HeartbeatResult handles both variants"() {
        expect:
        describe(result) == expected

        where:
        result | expected
        new HeartbeatResult.Beaten(new ClaimVersion('m1', Instant.parse('2026-07-29T10:15:30Z'))) | 'beaten: m1'
        new HeartbeatResult.ClaimGone() | 'gone'
    }

    // FR5, FR8: results are values — equal content means equal results
    def "results with the same components are equal values"() {
        given:
        def at = Instant.parse('2026-07-29T10:15:30Z')

        expect:
        new HeartbeatResult.ClaimGone() == new HeartbeatResult.ClaimGone()
        new HeartbeatResult.Beaten(new ClaimVersion('m1', at)) == new HeartbeatResult.Beaten(new ClaimVersion('m1', at))
        new HeartbeatResult.Beaten(new ClaimVersion('m1', at)) != new HeartbeatResult.Beaten(new ClaimVersion('m2', at))
    }

    private static String describe(HeartbeatResult result) {
        switch (result) {
            case HeartbeatResult.Beaten: return 'beaten: ' + ((HeartbeatResult.Beaten) result).version().markerId()
            case HeartbeatResult.ClaimGone: return 'gone'
            default: throw new IllegalStateException('unreachable')
        }
    }
}
