package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import com.github.oinsio.gnomish.serveobservability.SweepCounts
import com.github.oinsio.gnomish.serveobservability.SweepVital
import java.time.Instant
import spock.lang.Specification

/**
 * {@link SandboxHygieneAlertEvaluator}, task 6.4 of add-serve-sandbox-lifecycle (NFR-O3, UX2): the
 * three hygiene conditions, their exact thresholds, and the two silences that matter — no sweep
 * data is not a stall, and a manual age-stop is not an incident.
 */
class SandboxHygieneAlertEvaluatorSpec extends Specification {

    static final Instant TICK_AT = Instant.parse('2026-08-06T09:00:00Z')

    private static SweepVital vital(Instant lastTickAt = TICK_AT, int consecutiveSkipped = 0) {
        new SweepVital(lastTickAt, 300L, SweepCounts.NONE, [], 0, consecutiveSkipped)
    }

    private static SweepActionRow row(SweepVerdictCategory category, String mode, String object = 'box') {
        new SweepActionRow(TICK_AT, object, 'main-box', mode, 'task-1', category, 'unowned running', null)
    }

    // NFR-O3: no sweep data is not evidence of a stall — an older snapshot or a daemon whose first
    //     tick has not landed must not raise anything.
    def "an absent sweep vital raises nothing"() {
        expect:
        SandboxHygieneAlertEvaluator.evaluate(SandboxHygieneView.absent(), TICK_AT.plusSeconds(100000)).isEmpty()
    }

    // NFR-O3: the sweep's own cadence travels in the snapshot, so overdue is k=3 x that cadence.
    def "the tick-overdue condition fires strictly past three times the sweep cadence"() {
        given:
        def view = new SandboxHygieneView(vital(), [], 0)

        expect:
        SandboxHygieneAlertEvaluator.evaluate(view, TICK_AT.plusSeconds(elapsed))
                .contains(new AlertCondition.SweepTickOverdue()) == overdue

        where:
        elapsed | overdue
        0 | false
        899 | false
        900 | false
        901 | true
        7200 | true
    }

    // NFR-O3: "three consecutive ticks report skipped-no-verdict" is the stall signal.
    def "the consecutive-skip condition fires at three ticks and names the run length"() {
        given:
        def view = new SandboxHygieneView(vital(TICK_AT, consecutive), [], 0)

        when:
        def flagged = SandboxHygieneAlertEvaluator.evaluate(view, TICK_AT)

        then:
        flagged.findAll {
            it instanceof AlertCondition.SweepTicksSkipped
        } == expected

        where:
        consecutive | expected
        0 | []
        1 | []
        2 | []
        3 | [
            new AlertCondition.SweepTicksSkipped(3)
        ]
        7 | [
            new AlertCondition.SweepTicksSkipped(7)
        ]
    }

    // UX2: a tracked stopped-orphan reads as "an instance died or hung", naming object and task.
    def "a tracked stopped-orphan raises a named incident"() {
        given:
        def view = new SandboxHygieneView(vital(), [
            row(SweepVerdictCategory.STOPPED_ORPHAN, 'tracked', 'zombie-box')
        ], 1)

        expect:
        SandboxHygieneAlertEvaluator.evaluate(view, TICK_AT) ==
                [
                    new AlertCondition.StoppedOrphanIncident('zombie-box', 'task-1', 'unowned running')
                ]
    }

    // UX2: "A stopped-orphan event with mode manual ... SHALL NOT raise the dead-instance alert" —
    //      it is a routine age-policy stop and stays in the breakdown and the actions table only.
    def "a manual age-stop and a disposal raise no incident"() {
        given:
        def view = new SandboxHygieneView(vital(), [action], 1)

        expect:
        SandboxHygieneAlertEvaluator.evaluate(view, TICK_AT).isEmpty()

        where:
        action << [
            row(SweepVerdictCategory.STOPPED_ORPHAN, 'manual'),
            row(SweepVerdictCategory.DISPOSED_AGED, 'tracked'),
            row(SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE, 'tracked')
        ]
    }

    // UX2: every zombie in the window is named — two dead instances are two incidents.
    def "each tracked stopped-orphan in the window raises its own incident"() {
        given:
        def view = new SandboxHygieneView(
                vital(),
                [
                    row(SweepVerdictCategory.STOPPED_ORPHAN, 'tracked', 'box-a'),
                    row(SweepVerdictCategory.STOPPED_ORPHAN, 'manual', 'box-b'),
                    row(SweepVerdictCategory.STOPPED_ORPHAN, 'tracked', 'box-c')
                ],
                3)

        expect:
        SandboxHygieneAlertEvaluator.evaluate(view, TICK_AT)*.objectName() == ['box-a', 'box-c']
    }

    // NFR-O3: an incident is a LEDGER fact — it must surface even when the snapshot carries no
    //     sweep vital at all (an older build, or a snapshot lost while the ledger survived).
    def "an incident surfaces without any sweep vital"() {
        given:
        def view = new SandboxHygieneView(null, [
            row(SweepVerdictCategory.STOPPED_ORPHAN, 'tracked')
        ], 1)

        expect:
        SandboxHygieneAlertEvaluator.evaluate(view, TICK_AT).size() == 1
    }

    // NFR-O3: the conditions are independent — a stalled AND overdue sweep with a zombie raises all.
    def "all three conditions can fire together"() {
        given:
        def view = new SandboxHygieneView(
                vital(TICK_AT, 3), [
                    row(SweepVerdictCategory.STOPPED_ORPHAN, 'tracked')
                ], 1)

        when:
        def flagged = SandboxHygieneAlertEvaluator.evaluate(view, TICK_AT.plusSeconds(7200))

        then:
        flagged.size() == 3
        flagged[0] instanceof AlertCondition.SweepTickOverdue
        flagged[1] instanceof AlertCondition.SweepTicksSkipped
        flagged[2] instanceof AlertCondition.StoppedOrphanIncident
    }
}
