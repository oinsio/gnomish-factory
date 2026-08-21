package com.github.oinsio.gnomish.app.sandboxlifecycle

import spock.lang.Specification

class SweepSummaryListenerSpec extends Specification {

    def delegated = []
    SweepVerdictListener delegate = { SweepVerdict v -> delegated << v }
    def listener = new SweepSummaryListener(delegate)

    private static SweepVerdict verdict(SweepVerdictCategory category) {
        new SweepVerdict(category, 'n', 'main-box', 'tracked', 'k1', 'reason', null)
    }

    def "forwards every verdict to the delegate"() {
        given:
        def v = verdict(SweepVerdictCategory.CHECKED_ALIVE)

        when:
        listener.onVerdict(v)

        then:
        delegated == [v]
    }

    def "summaryLine reports nothing to report before any verdict"() {
        expect:
        listener.summaryLine() == 'sweep: nothing to report'
    }

    def "summaryLine tallies counts per category"() {
        when:
        listener.onVerdict(verdict(SweepVerdictCategory.CHECKED_ALIVE))
        listener.onVerdict(verdict(SweepVerdictCategory.CHECKED_ALIVE))
        listener.onVerdict(verdict(SweepVerdictCategory.DISPOSED_AGED))

        then:
        listener.summaryLine() == 'sweep: 2 checked-alive, 1 disposed-aged'
    }

    def "summaryLine renders every category label distinctly"() {
        when:
        listener.onVerdict(verdict(category))

        then:
        listener.summaryLine() == "sweep: 1 ${label}"

        where:
        category | label
        SweepVerdictCategory.CHECKED_ALIVE | 'checked-alive'
        SweepVerdictCategory.KEPT_UNDER_THRESHOLD | 'kept-under-threshold'
        SweepVerdictCategory.STOPPED_ORPHAN | 'stopped-orphan'
        SweepVerdictCategory.DISPOSED_AGED | 'disposed-aged'
        SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE | 'disposed-reconstructible'
        SweepVerdictCategory.SKIPPED_NO_VERDICT | 'skipped-no-verdict'
    }
}
