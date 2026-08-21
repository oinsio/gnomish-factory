package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory

/**
 * `sandbox-lifecycle` "Minimum object age protection" and the two reap thresholds, at the
 * nanosecond: each comparison is inclusive at the threshold and protective one nanosecond under
 * it. Kept apart from the matrix-row specs because these features assert the comparison operator
 * itself, not the row it gates.
 *
 * <p>FR4, FR5, FR7 of add-serve-sandbox-lifecycle.
 */
class SandboxLifecycleDecisionBoundarySpec extends SandboxLifecycleDecisionSpecBase {

    def "the container minimum-age boundary: exactly at the threshold proceeds, one nanosecond under does not"() {
        when: 'exactly at the minimum age — proceeds to the fresh-claim check'
        decision.decideContainer(
                obj(),
                cls('alive', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED),
                running(NOW - MIN_AGE, NOW - MIN_AGE),
                LIVE,
                NOW,
                THRESHOLDS)

        then:
        verdicts[0].reason() == 'fresh claim'

        when: 'one nanosecond under the minimum age — untouched for that reason instead'
        decision.decideContainer(
                obj(),
                cls('alive', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED),
                running((NOW - MIN_AGE).plusNanos(1), (NOW - MIN_AGE).plusNanos(1)),
                LIVE,
                NOW,
                THRESHOLDS)

        then:
        verdicts[1].reason() == 'under minimum object age'
    }

    def "the remnant minimum-age boundary: exactly at the threshold proceeds, one nanosecond under does not"() {
        given:
        def net = network('gnomish-net-alive')

        when: 'exactly at the minimum age — proceeds to the fresh-claim check'
        decision.decideRemnant(
                net, cls('alive', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), NOW - MIN_AGE, LIVE, NOW,
                THRESHOLDS)

        then:
        verdicts[0].reason() == 'fresh claim'

        when: 'one nanosecond under the minimum age — untouched for that reason instead'
        decision.decideRemnant(
                net, cls('alive', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), (NOW - MIN_AGE).plusNanos(1), LIVE,
                NOW, THRESHOLDS)

        then:
        verdicts[1].reason() == 'under minimum object age'
    }

    def "the manual running-threshold boundary: exactly at it stops, one nanosecond under does not"() {
        when: 'one nanosecond under the threshold — still protected'
        decision.decideContainer(
                obj(),
                cls('x', ObjectRole.MAIN_BOX, OwnershipMode.MANUAL),
                running(OLD, (NOW - MANUAL_THRESHOLD).plusNanos(1)),
                NO_VERDICT,
                NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.CHECKED_ALIVE

        when: 'exactly at the threshold — stopped'
        decision.decideContainer(
                obj('gnomish-box-x'),
                cls('x', ObjectRole.MAIN_BOX, OwnershipMode.MANUAL),
                running(OLD, NOW - MANUAL_THRESHOLD),
                NO_VERDICT,
                NOW,
                THRESHOLDS)

        then:
        verdicts[1].category() == SweepVerdictCategory.STOPPED_ORPHAN
    }

    def "the reap-age boundary: exactly at the threshold disposes, one nanosecond under keeps"() {
        when: 'one nanosecond under the reap threshold'
        decision.decideContainer(
                obj('a'),
                cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED),
                stopped(OLD, (NOW - REAP).plusNanos(1)),
                UNOWNED,
                NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.KEPT_UNDER_THRESHOLD

        when: 'exactly at the reap threshold'
        decision.decideContainer(
                obj('b'),
                cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED),
                stopped(OLD, NOW - REAP),
                UNOWNED,
                NOW,
                THRESHOLDS)

        then:
        verdicts[1].category() == SweepVerdictCategory.DISPOSED_AGED
    }
}
