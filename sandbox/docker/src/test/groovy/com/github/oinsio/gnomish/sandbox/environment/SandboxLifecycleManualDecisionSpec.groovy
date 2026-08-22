package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import java.time.Duration

/**
 * `sandbox-lifecycle` "Manual mode is governed by age alone" (design D5): the manual-mode rows of
 * the decision matrix — a running session protected until its own threshold, every role
 * age-protected rather than disposed on sight, and the shared aged-reap for stopped objects — plus
 * the reason strings that let a sink tell a routine manual age-stop from a dead-instance symptom.
 *
 * <p>FR7, FR9 of add-serve-sandbox-lifecycle.
 */
class SandboxLifecycleManualDecisionSpec extends SandboxLifecycleDecisionSpecBase {

    def "a manual running box within the threshold is untouched"() {
        when:
        decision.decideContainer(
                obj(),
                cls('x', ObjectRole.MAIN_BOX, OwnershipMode.MANUAL),
                running(OLD, NOW - Duration.ofHours(1)),
                NO_VERDICT,
                NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.CHECKED_ALIVE
        verdicts[0].reason() == 'manual session within running threshold'
        docker.runs.isEmpty()
    }

    def "a manual running box past the threshold is stopped, emitting mode manual"() {
        when:
        decision.decideContainer(
                obj('gnomish-box-x'),
                cls('x', ObjectRole.MAIN_BOX, OwnershipMode.MANUAL),
                running(OLD, NOW - Duration.ofHours(25)),
                NO_VERDICT,
                NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.STOPPED_ORPHAN
        verdicts[0].mode() == 'manual'
        docker.runs == [
            DockerCommands.stop('gnomish-box-x')
        ]
    }

    def "a manual guard/judge/verification/seed-helper is protected by age, never disposed on sight"() {
        when:
        decision.decideContainer(
                obj('n'),
                cls('x', role, OwnershipMode.MANUAL),
                running(OLD, NOW - Duration.ofHours(1)),
                NO_VERDICT,
                NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.CHECKED_ALIVE
        docker.runs.isEmpty()
        0 * disposal.dispose(_)

        where:
        role << [
            ObjectRole.GUARD,
            ObjectRole.JUDGE,
            ObjectRole.VERIFICATION,
            ObjectRole.SEED_HELPER
        ]
    }

    def "manual stopped objects follow the same aged-reap threshold as tracked ones"() {
        when:
        decision.decideContainer(
                obj('n'),
                cls('x', ObjectRole.MAIN_BOX, OwnershipMode.MANUAL),
                stopped(OLD, NOW - Duration.ofDays(8)),
                NO_VERDICT,
                NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.DISPOSED_AGED
    }

    def "the running-stop and stopped-reap reasons distinguish tracked-unowned from manual"() {
        when: 'tracked unowned running'
        decision.decideContainer(
                obj('a'), cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), running(), UNOWNED, NOW, THRESHOLDS)

        then:
        verdicts[0].reason() == 'unowned running main-box'

        when: 'manual running past threshold'
        decision.decideContainer(
                obj('b'), cls('x', ObjectRole.MAIN_BOX, OwnershipMode.MANUAL), running(OLD, OLD), NO_VERDICT, NOW,
                THRESHOLDS)

        then:
        verdicts[1].reason() == 'manual running past threshold'

        when: 'tracked unowned stopped, kept'
        decision.decideContainer(
                obj('c'),
                cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED),
                stopped(OLD, NOW - Duration.ofHours(1)),
                UNOWNED,
                NOW,
                THRESHOLDS)

        then:
        verdicts[2].reason() == 'unowned stopped'

        when: 'manual stopped, kept'
        decision.decideContainer(
                obj('d'),
                cls('x', ObjectRole.MAIN_BOX, OwnershipMode.MANUAL),
                stopped(OLD, NOW - Duration.ofHours(1)),
                NO_VERDICT,
                NOW,
                THRESHOLDS)

        then:
        verdicts[3].reason() == 'manual stopped'
    }
}
