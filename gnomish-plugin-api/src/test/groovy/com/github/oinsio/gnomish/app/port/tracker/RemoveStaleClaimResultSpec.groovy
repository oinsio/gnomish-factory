package com.github.oinsio.gnomish.app.port.tracker

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import spock.lang.Specification

/**
 * RemoveStaleClaimResult: the outcome of removeStaleClaim — Removed, the task is
 * back in circulation, or Mismatch(currentVersion), a safe no-op reporting the
 * live claim facts when the observed version no longer matches (design D5). A
 * null currentVersion means the claim is already gone. Implements FR4, FR5 of
 * add-claim-heartbeat.
 */
class RemoveStaleClaimResultSpec extends Specification {

    // FR5: Mismatch reports the current live claim version when the observed one no longer matches
    def "Mismatch exposes the current claim version exactly as constructed"() {
        given:
        def current = new ClaimVersion('claim-comment-992', Instant.parse('2026-07-29T10:20:30Z'), new ClaimEpoch(1))

        expect:
        new RemoveStaleClaimResult.Mismatch(current).currentVersion() == current
    }

    // FR4, NFR-R2: a claim already removed by a racing reaper is a Mismatch with no live version
    def "Mismatch carries a null current version when the claim is already gone"() {
        expect:
        new RemoveStaleClaimResult.Mismatch(null).currentVersion() == null
    }

    // FR4, FR5: RemoveStaleClaimResult is sealed — an exhaustive switch handles both variants
    def "an exhaustive switch over RemoveStaleClaimResult handles both variants"() {
        expect:
        describe(result) == expected

        where:
        result | expected
        new RemoveStaleClaimResult.Removed() | 'removed'
        new RemoveStaleClaimResult.Mismatch(new ClaimVersion('m2', Instant.parse('2026-07-29T10:20:30Z'), new ClaimEpoch(1))) | 'mismatch: m2'
        new RemoveStaleClaimResult.Mismatch(null) | 'mismatch: gone'
    }

    // FR4, FR5: results are values — equal content means equal results
    def "results with the same components are equal values"() {
        given:
        def at = Instant.parse('2026-07-29T10:20:30Z')

        expect:
        new RemoveStaleClaimResult.Removed() == new RemoveStaleClaimResult.Removed()
        new RemoveStaleClaimResult.Mismatch(new ClaimVersion('m2', at, new ClaimEpoch(1))) ==
                new RemoveStaleClaimResult.Mismatch(new ClaimVersion('m2', at, new ClaimEpoch(1)))
        new RemoveStaleClaimResult.Mismatch(new ClaimVersion('m2', at, new ClaimEpoch(1))) !=
                new RemoveStaleClaimResult.Mismatch(new ClaimVersion('m3', at, new ClaimEpoch(1)))
        new RemoveStaleClaimResult.Mismatch(null) != new RemoveStaleClaimResult.Mismatch(new ClaimVersion('m2', at, new ClaimEpoch(1)))
    }

    private static String describe(RemoveStaleClaimResult result) {
        switch (result) {
            case RemoveStaleClaimResult.Removed:
                return 'removed'
            case RemoveStaleClaimResult.Mismatch:
                def current = ((RemoveStaleClaimResult.Mismatch) result).currentVersion()
                return 'mismatch: ' + (current == null ? 'gone' : current.markerId())
            default:
                throw new IllegalStateException('unreachable')
        }
    }
}
