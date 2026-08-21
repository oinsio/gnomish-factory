package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import java.time.Duration

/**
 * `sandbox-lifecycle` "Sweep decision matrix", container-less-remnant rows: {@link
 * SandboxLifecycleDecision#decideRemnant} applies the same minimum-age, ownership and reap-age
 * rules to a volume or network whose container is gone — in BOTH ownership modes, since a manual
 * session leaves remnants exactly as a tracked one does and has no oracle to be judged by.
 *
 * <p>FR4, FR5, FR7, FR9, NFR-S2 of add-serve-sandbox-lifecycle.
 */
class SandboxLifecycleRemnantDecisionSpec extends SandboxLifecycleDecisionSpecBase {

    def "the same minimum-age, ownership, and reap-age rules apply to a container-less volume"() {
        given:
        def vol = volume('gnomish-vol-x')

        when: 'kept under threshold'
        decision.decideRemnant(
                vol, cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), NOW - Duration.ofDays(1), UNOWNED, NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.KEPT_UNDER_THRESHOLD

        when: 'past the reap threshold'
        decision.decideRemnant(
                vol, cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), NOW - Duration.ofDays(8), UNOWNED, NOW,
                THRESHOLDS)

        then:
        verdicts[1].category() == SweepVerdictCategory.DISPOSED_AGED
        1 * disposal.dispose('x')
    }

    def "an alive remnant is left untouched"() {
        when:
        decision.decideRemnant(
                network('gnomish-net-alive'), cls('alive', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), OLD, LIVE, NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.CHECKED_ALIVE
        0 * disposal.dispose(_)
    }

    def "an absent liveness verdict skips a tracked remnant, and a disposable-on-sight role disposes on sight"() {
        when: 'no liveness verdict'
        decision.decideRemnant(
                volume('gnomish-vol-x'), cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), OLD, NO_VERDICT, NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.SKIPPED_NO_VERDICT
        0 * disposal.dispose(_)

        when: 'a judge-role remnant, unowned, disposes on sight rather than waiting for the reap age'
        decision.decideRemnant(
                volume('gnomish-vol-base-j'), cls('base-j', ObjectRole.JUDGE, OwnershipMode.TRACKED, 'base'), OLD,
                UNOWNED, NOW, THRESHOLDS)

        then:
        verdicts[1].category() == SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE
        1 * disposal.dispose('base-j')
    }

    // FR7: a manual remnant has no claim to consult, so the oracle never gates it — neither to
    // spare it (a "fresh claim" it can never have) nor to skip it (an absent verdict a manual
    // object is not entitled to wait for). Age alone decides, in both directions.
    def "a manual remnant is judged by age alone, whatever the liveness verdict says"() {
        when:
        decision.decideRemnant(
                volume('gnomish-vol-m'), cls('m', ObjectRole.MAIN_BOX, OwnershipMode.MANUAL),
                NOW - Duration.ofDays(age), liveness, NOW, THRESHOLDS)

        then:
        verdicts[0].category() == expected
        verdicts[0].mode() == 'manual'

        where:
        age | liveness | expected
        1 | NO_VERDICT | SweepVerdictCategory.KEPT_UNDER_THRESHOLD
        1 | UNOWNED | SweepVerdictCategory.KEPT_UNDER_THRESHOLD
        1 | LIVE | SweepVerdictCategory.KEPT_UNDER_THRESHOLD
        8 | NO_VERDICT | SweepVerdictCategory.DISPOSED_AGED
        8 | UNOWNED | SweepVerdictCategory.DISPOSED_AGED
        8 | LIVE | SweepVerdictCategory.DISPOSED_AGED
    }

    // NFR-O4: the reason names the ownership mode, exactly as the container path's "manual stopped"
    // / "unowned stopped" pair does — a sink reading reason strings must not have to fall back to
    // the mode field on the remnant path alone to tell a routine manual reap from a dead-instance
    // symptom.
    def "the remnant reason distinguishes manual from tracked-unowned, like the stopped-box reason"() {
        when:
        decision.decideRemnant(
                volume('gnomish-vol-x'), cls('x', ObjectRole.MAIN_BOX, mode), NOW - Duration.ofDays(age), UNOWNED,
                NOW, THRESHOLDS)

        then:
        verdicts[0].reason() == expectedReason

        where:
        mode | age | expectedReason
        OwnershipMode.TRACKED | 1 | 'unowned remnant'
        OwnershipMode.MANUAL | 1 | 'manual remnant'
        OwnershipMode.TRACKED | 8 | 'unowned remnant, past reap threshold'
        OwnershipMode.MANUAL | 8 | 'manual remnant, past reap threshold'
    }

    // FR7: the disposable-on-sight shortcut is a TRACKED-mode rule (an unowned judge box is
    // reconstructible because its claim says nobody holds it). A manual judge or verification
    // remnant has no such signal, so it keeps its full reap-age protection like any manual object.
    def "a manual disposable-on-sight remnant keeps its age protection instead of being disposed on sight"() {
        when:
        decision.decideRemnant(
                volume('gnomish-vol-base-j'), cls('base-j', role, OwnershipMode.MANUAL, 'base'),
                NOW - Duration.ofDays(1), UNOWNED, NOW, THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.KEPT_UNDER_THRESHOLD
        0 * disposal.dispose(_)

        where:
        role << [
            ObjectRole.GUARD,
            ObjectRole.JUDGE,
            ObjectRole.VERIFICATION,
            ObjectRole.SEED_HELPER
        ]
    }

    def "a manual remnant under the minimum age is untouched, like a tracked one"() {
        when:
        decision.decideRemnant(
                volume('gnomish-vol-m'), cls('m', ObjectRole.MAIN_BOX, OwnershipMode.MANUAL),
                NOW - Duration.ofSeconds(1), NO_VERDICT, NOW, THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.CHECKED_ALIVE
        verdicts[0].reason() == 'under minimum object age'
        docker.runs.isEmpty()
        0 * disposal.dispose(_)
    }

    // The unrecognized object's own name matches no factory pattern, so the environment key's
    // triple belongs to some OTHER object: disposing by key would destroy that one and leave this
    // one running.
    def "an unrecognized object is removed by its own name and kind, never by its environment key"() {
        when:
        decision.decideRemnant(
                new ListedDockerObject(name, kind, [:]), cls('x', ObjectRole.UNRECOGNIZED, OwnershipMode.TRACKED), OLD,
                UNOWNED, NOW, THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.DISPOSED_AGED
        docker.runs == [expectedArgv]
        0 * disposal.dispose(_)

        where:
        kind | name | expectedArgv
        ObjectKind.CONTAINER | 'mystery' | DockerCommands.removeContainer('mystery')
        ObjectKind.VOLUME | 'mystery-vol' | DockerCommands.removeVolume('mystery-vol')
        ObjectKind.NETWORK | 'mystery-net' | DockerCommands.removeNetwork('mystery-net')
    }
}
