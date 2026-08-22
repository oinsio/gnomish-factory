package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import java.time.Duration

/**
 * `sandbox-lifecycle` "Sweep decision matrix": the tracked-mode rows, driven directly against
 * {@link SandboxLifecycleDecision} with hand-built inputs — the data-driven table task 3.2
 * requires. Manual-mode rows live in {@link SandboxLifecycleManualDecisionSpec}, container-less
 * remnants in {@link SandboxLifecycleRemnantDecisionSpec}, and every threshold boundary in {@link
 * SandboxLifecycleDecisionBoundarySpec}.
 *
 * <p>FR4, FR5, FR9, NFR-S2, M4 of add-serve-sandbox-lifecycle.
 */
class SandboxLifecycleTrackedDecisionSpec extends SandboxLifecycleDecisionSpecBase {

    def "an object under the minimum age is untouched regardless of ownership or role"() {
        given:
        def justBorn = NOW - Duration.ofSeconds(1)

        when:
        decision.decideContainer(
                obj(),
                cls('unowned', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED),
                running(justBorn, justBorn),
                NO_VERDICT,
                NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.CHECKED_ALIVE
        verdicts[0].reason() == 'under minimum object age'
        docker.runs.isEmpty()
        0 * disposal.dispose(_)
    }

    def "tracked alive by fresh claim is untouched, for any role"() {
        when:
        decision.decideContainer(obj(), cls('alive', role, OwnershipMode.TRACKED), running(), LIVE, NOW, THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.CHECKED_ALIVE
        verdicts[0].reason() == 'fresh claim'
        docker.runs.isEmpty()

        where:
        role << [
            ObjectRole.MAIN_BOX,
            ObjectRole.GUARD,
            ObjectRole.JUDGE,
            ObjectRole.VERIFICATION,
            ObjectRole.SEED_HELPER,
            ObjectRole.UNRECOGNIZED
        ]
    }

    def "an absent liveness verdict skips every tracked object with no-verdict, for any role"() {
        when:
        decision.decideContainer(obj(), cls('x', role, OwnershipMode.TRACKED), running(), NO_VERDICT, NOW, THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.SKIPPED_NO_VERDICT
        docker.runs.isEmpty()
        0 * disposal.dispose(_)

        where:
        role << [
            ObjectRole.MAIN_BOX,
            ObjectRole.GUARD,
            ObjectRole.JUDGE,
            ObjectRole.VERIFICATION,
            ObjectRole.SEED_HELPER,
            ObjectRole.UNRECOGNIZED
        ]
    }

    def "unowned running main box is stopped, never disposed"() {
        when:
        decision.decideContainer(
                obj('gnomish-box-x'), cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), running(), UNOWNED, NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.STOPPED_ORPHAN
        docker.runs == [
            DockerCommands.stop('gnomish-box-x')
        ]
        0 * disposal.dispose(_)
    }

    def "unowned stopped main box is kept under threshold, then disposed past it"() {
        when:
        decision.decideContainer(
                obj('gnomish-box-x'),
                cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED),
                stopped(OLD, NOW - Duration.ofDays(finishedAgeDays)),
                UNOWNED,
                NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == expectedCategory

        where:
        finishedAgeDays | expectedCategory
        1 | SweepVerdictCategory.KEPT_UNDER_THRESHOLD
        8 | SweepVerdictCategory.DISPOSED_AGED
    }

    def "an unowned guard/judge/verification/seed-helper object is disposed on sight, never kept"() {
        when:
        decision.decideContainer(obj('n'), cls('x', role, OwnershipMode.TRACKED), running(), UNOWNED, NOW, THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE

        where:
        role << [
            ObjectRole.GUARD,
            ObjectRole.JUDGE,
            ObjectRole.VERIFICATION,
            ObjectRole.SEED_HELPER
        ]
    }

    def "disposing a guard or seed helper removes only that named container, never the bundled key"() {
        when:
        decision.decideContainer(
                obj('gnomish-guard-x'), cls('x', role, OwnershipMode.TRACKED), running(), UNOWNED, NOW, THRESHOLDS)

        then:
        docker.runs == [
            DockerCommands.removeContainer('gnomish-guard-x')
        ]
        0 * disposal.dispose(_)

        where:
        role << [
            ObjectRole.GUARD,
            ObjectRole.SEED_HELPER
        ]
    }

    def "disposing a judge or verification object disposes its own exclusive key triple"() {
        when:
        decision.decideContainer(
                obj('gnomish-box-base-j'), cls('base-j', role, OwnershipMode.TRACKED, 'base'), running(), UNOWNED, NOW,
                THRESHOLDS)

        then:
        1 * disposal.dispose('base-j')
        docker.runs == [
            DockerLifecycleCommands.inspectExists(ObjectKind.CONTAINER, 'gnomish-box-base-j')
        ]

        where:
        role << [
            ObjectRole.JUDGE,
            ObjectRole.VERIFICATION
        ]
    }

    def "an unrecognized role is stopped if running, then aged-reaped once stopped"() {
        when: 'running'
        decision.decideContainer(
                obj('mystery'), cls('x', ObjectRole.UNRECOGNIZED, OwnershipMode.TRACKED), running(), UNOWNED, NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.STOPPED_ORPHAN
        docker.runs == [
            DockerCommands.stop('mystery')
        ]

        when: 'stopped, past the reap threshold'
        decision.decideContainer(
                obj('mystery'), cls('x', ObjectRole.UNRECOGNIZED, OwnershipMode.TRACKED), stopped(OLD, OLD), UNOWNED,
                NOW, THRESHOLDS)

        then:
        verdicts[1].category() == SweepVerdictCategory.DISPOSED_AGED
    }

    def "the verdict carries the object's own role label, distinguishing every role"() {
        when:
        decision.decideContainer(obj(), cls('alive', role, OwnershipMode.TRACKED), running(), LIVE, NOW, THRESHOLDS)

        then:
        verdicts[0].role() == label

        where:
        role | label
        ObjectRole.MAIN_BOX | 'main-box'
        ObjectRole.GUARD | 'guard'
        ObjectRole.JUDGE | 'judge'
        ObjectRole.VERIFICATION | 'verification'
        ObjectRole.SEED_HELPER | 'seed-helper'
        ObjectRole.UNRECOGNIZED | 'unrecognized'
    }
}
