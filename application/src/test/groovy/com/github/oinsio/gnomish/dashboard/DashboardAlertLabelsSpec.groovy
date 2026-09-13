package com.github.oinsio.gnomish.dashboard

import java.time.Instant
import spock.lang.Specification

/**
 * FR4, UX3 of add-dashboard-page; NFR-O3, UX6 of add-base-ref-resolution: {@link
 * DashboardAlertLabels#label} for {@link AlertCondition.RemoteGateOpen} escalates its verb from
 * "remote outage, probing" to "blocked on the remote" once the condition itself reports {@code
 * sustainedOpen}.
 */
class DashboardAlertLabelsSpec extends Specification {

    private static AlertCondition.RemoteGateOpen gate(boolean sustainedOpen) {
        new AlertCondition.RemoteGateOpen('origin', Instant.EPOCH, 'connection refused', Instant.EPOCH.plusSeconds(60), sustainedOpen)
    }

    def "a not-yet-sustained open gate reads as probing"() {
        expect:
        DashboardAlertLabels.label(gate(false)).startsWith('remote outage, probing')
    }

    def "a sustained-open gate reads as blocked on the remote"() {
        expect:
        DashboardAlertLabels.label(gate(true)).startsWith('blocked on the remote')
    }
}
