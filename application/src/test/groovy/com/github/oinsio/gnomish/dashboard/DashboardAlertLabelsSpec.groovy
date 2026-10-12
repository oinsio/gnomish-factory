package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.app.sandboxlifecycle.SkippedVerdictSource
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

    // Scenario: observability/dashboard-page / A sustained outage escalates the wording
    def "a sustained-open gate reads as blocked on the remote"() {
        expect:
        DashboardAlertLabels.label(gate(true)).startsWith('blocked on the remote')
    }

    // NFR-O3 of add-serve-sandbox-lifecycle; F90 of tighten-spec-contract: the stall line says
    //     cleanup is not running and names where the verdicts failed, in the sweep's vocabulary.
    def "a stalled sweep names the run length and the failing verdict source"() {
        expect:
        DashboardAlertLabels.label(new AlertCondition.SweepTicksSkipped(4, source)) ==
                'sandbox cleanup stalled: 4 consecutive ticks reached no claim verdict (failing verdict source: ' +
                named + ')'

        where:
        source | named
        SkippedVerdictSource.TRACKER | 'tracker'
        SkippedVerdictSource.RUNTIME | 'runtime'
    }

    // F90: a snapshot written before the source was carried has none — the line says what it knows.
    def "a stalled sweep without a recorded source names only the run length"() {
        expect:
        DashboardAlertLabels.label(new AlertCondition.SweepTicksSkipped(3, null)) ==
                'sandbox cleanup stalled: 3 consecutive ticks reached no claim verdict'
    }
}
