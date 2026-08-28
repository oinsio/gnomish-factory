package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.contract.TrackerKillWindows
import spock.lang.Specification

/**
 * The classifying half of the tracker kill-point harness (task 9.1b, FR19, M1 of
 * harden-task-branch-contract): every fact combination a killed tracker sequence freezes classifies
 * to a named shape with an owner that converges it.
 *
 * <p>The freezing half is {@code GithubKillWindowSpec}, in the one adapter whose writes are
 * physically non-atomic; it asserts that each window it freezes is enumerated by {@link
 * TrackerKillWindows}, and this spec asserts what those enumerated windows classify to. The split
 * is the module boundary: {@link TrackerShapeClassifier} lives here, and the vendor bundle must not
 * depend on this module (FR2 of split-into-modules).
 */
class TrackerKillWindowShapeSpec extends Specification {

    def "FR19: the frozen window #signature classifies to a named shape, never Foreign"() {
        when:
        def shape = TrackerShapeClassifier.classify(TrackerKillWindows.facts(signature))

        then: 'a named shape'
        !(shape instanceof TrackerShape.Foreign)

        and: 'owned by someone who converges it — a retry, the queue, or the sweep'
        shape.recoveryOwner() != TrackerRecoveryOwner.NONE

        and: 'a non-steady window is exactly what the reaper repairs'
        shape.isSteady() || shape.recoveryOwner() == TrackerRecoveryOwner.REAPER

        where:
        signature << TrackerKillWindows.SIGNATURES
    }

    // A window nobody records is a window nobody checks: the list is the contract between the two
    // halves, so its emptiness is worth one assertion of its own.
    def "FR19: the enumerated window list is not empty"() {
        expect:
        !TrackerKillWindows.SIGNATURES.isEmpty()
    }
}
