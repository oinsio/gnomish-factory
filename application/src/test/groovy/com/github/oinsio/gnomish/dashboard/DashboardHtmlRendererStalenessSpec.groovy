package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.emptyHistory
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.neverFetchedBoard
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.noSweepData

import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Verifies {@link DashboardHtmlRenderer}'s watch-mode staleness banner
 * (task 3.3): a {@code --watch} render bakes {@code generatedAt} and a
 * cadence-derived threshold (k = 3 × the render cadence, design D4) into an
 * inline script plus an initially-hidden banner element; a one-shot render
 * (no cadence) carries neither, showing only its {@code generatedAt} as
 * plain information. The JS itself is asserted at the string level — the
 * baked literals are exactly what a browser's comparison would use, so
 * correctness is evident without executing it (M3).
 *
 * FR8, M3 of add-dashboard-page (design D3, D4).
 */
class DashboardHtmlRendererStalenessSpec extends Specification {

    def renderer = new DashboardHtmlRenderer()

    private static final Instant GENERATED_AT = Instant.parse('2026-08-06T09:00:00Z')

    def "watch-mode page bakes generatedAt millis and the k=3 threshold, and includes the banner script and markup"() {
        given:
        def cadence = Duration.ofSeconds(10)

        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), noSweepData(), GENERATED_AT, cadence)

        then: 'the baked generatedAt instant, in epoch millis'
        html.contains(String.valueOf(GENERATED_AT.toEpochMilli()))

        and: 'the staleness threshold is exactly cadence (10s) x k (3) = 30000ms'
        html.contains('30000')

        and: 'the inline detection script and the banner element are present'
        html.contains('<script>')
        html.contains('id="staleness-banner"')
        html.contains('Date.now()')
        html.contains("getElementById('staleness-banner')")

        and: 'a meta-refresh with the render cadence (10 s) is baked into the head so the tab reloads itself (FR7)'
        html.contains('<meta http-equiv="refresh" content="10">')

        and: 'the banner is a full-page overlay, not a top strip — it covers the page when stale (FR8)'
        html.contains('inset: 0;')
    }

    @Unroll
    def "threshold is cadence (#cadenceSeconds s) x k=3 = #expectedThresholdMillis ms"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(),
                emptyHistory(),
                neverFetchedBoard(),
                noSweepData(),
                GENERATED_AT,
                Duration.ofSeconds(cadenceSeconds))

        then:
        html.contains(String.valueOf(expectedThresholdMillis))

        where:
        cadenceSeconds | expectedThresholdMillis
        10 | 30000
        5 | 15000
        60 | 180000
    }

    def "one-shot page (no render cadence) shows generatedAt but includes no staleness script or banner"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), noSweepData(), GENERATED_AT, null)

        then: 'the generatedAt is still shown, as plain information'
        html.contains(GENERATED_AT.toString())

        and: 'no watch-mode staleness detection script or banner markup is baked in'
        !html.contains('<script>')
        !html.contains('Date.now()')
        !html.contains('id="staleness-banner"')
        !html.contains("getElementById('staleness-banner')")

        and: 'no meta-refresh is baked into a one-shot page (FR7)'
        !html.contains('http-equiv="refresh"')
    }
}
