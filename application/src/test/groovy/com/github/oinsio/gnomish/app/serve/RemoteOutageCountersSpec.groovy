package com.github.oinsio.gnomish.app.serve

import spock.lang.Specification

/**
 * FR14, NFR-O1, NFR-O3 of add-base-ref-resolution: {@link RemoteOutageCounters} in isolation —
 * {@link RemoteOutageGate} exercises it only through a live open/probe/close sequence, so this spec
 * pins each count's own read-modify-write behavior directly, including {@code opened()} resetting
 * every count for a freshly opened outage even when the previous outage left them non-zero.
 */
class RemoteOutageCountersSpec extends Specification {

    def "opened resets every count, even carrying over non-zero values from a previous outage"() {
        given: 'a previous outage left every count non-zero'
        def counters = new RemoteOutageCounters()
        counters.opened()
        counters.probeFailed()
        counters.probeFailed()
        counters.claimReleased()

        when:
        counters.opened()

        then: 'one failure so far, no probes, no releases'
        counters.consecutiveFailures() == 1
        counters.failedProbeCount() == 0
        counters.releasedClaims() == 0
    }

    def "probeFailed increments both the probe count and the consecutive-failure count"() {
        given:
        def counters = new RemoteOutageCounters()
        counters.opened()

        when:
        counters.probeFailed()
        counters.probeFailed()

        then:
        counters.failedProbeCount() == 2
        counters.consecutiveFailures() == 3
    }

    def "claimReleased increments the released-claims count"() {
        given:
        def counters = new RemoteOutageCounters()

        when:
        counters.claimReleased()
        counters.claimReleased()
        counters.claimReleased()

        then:
        counters.releasedClaims() == 3
    }

    def "closed clears the consecutive-failure count, leaving probe and released counts readable"() {
        given:
        def counters = new RemoteOutageCounters()
        counters.opened()
        counters.probeFailed()
        counters.claimReleased()

        when:
        counters.closed()

        then:
        counters.consecutiveFailures() == 0
        counters.failedProbeCount() == 1
        counters.releasedClaims() == 1
    }
}
