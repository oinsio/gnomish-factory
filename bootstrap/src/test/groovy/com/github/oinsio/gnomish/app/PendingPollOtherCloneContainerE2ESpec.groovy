package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.HostVerificationKill.registerRef
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*

import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Timeout
/**
 * Task 4.3b of migrate-verify-to-executor-contract (design D21, D22; NFR-R1, NFR-R4), the container
 * medium picked up on another clone before any pending commit reached origin ({@link
 * PendingPollOtherCloneE2ESpecBase}). The snapshot is not pushed by the round (D21), but an {@code
 * external} check's attempt delivery pushes it, with its register, before the check starts: killed
 * before that delivery (W0), origin's register is the previous closing commit, so the stage executor
 * re-runs and no attempt is burned; killed after it (W1, and W1′ whose pending commit's push was
 * lost), origin's register is the snapshot, and the round is re-verified without its agent, as on the
 * clone that wrote it. Docker- and guard-image-gated.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class PendingPollOtherCloneContainerE2ESpec extends PendingPollOtherCloneE2ESpecBase {

    def "W0 on another clone, killed before the attempt delivery: origin's register is the previous closing commit, so the stage executor re-runs and no attempt is burned"() {
        given:
        taskId = "PEND-A-${System.nanoTime() % 100000}"
        def pipeline = gated([
            gate(server, 'gate', GATE, Duration.ofSeconds(1))
        ], 'work')
        script(server, ['RUNNING', 'OK'])

        when: 'the dying instance registered its snapshot and died before the delivery an external check waits for'
        freshRunOver(pipeline) {
            PendingKills.killedAt(PendingKills.Point.BEFORE_DELIVERY, it)
        }

        then: 'the snapshot never reached origin, whose register is still the task\'s creation'
        thrown(RunKills.SimulatedKill)
        def snapshot = commitOf(cloneDir, registerRef(taskId))
        subjectOf(cloneDir, snapshot).startsWith('gnomish: snapshot work#0 ')
        def before = remote(registerRef(taskId))
        before != snapshot
        asked() == 0

        when: 'a second instance on another clone resumes the task'
        def other = otherClone()
        resume(pipeline, other)

        then: 'the agent ran once more, under a round opened on origin\'s register; its check started and closed'
        def subjects = gitOutput(other, 'log', '--reverse', '--format=%s', branch()).readLines()
        subjects.findAll {
            it.startsWith('gnomish: snapshot work#')
        } == [
            "gnomish: snapshot work#0 ${before}".toString()
        ]
        subjects.count { it.startsWith('gnomish: pending work#0 ') } == 1
        subjects.findAll {
            it.startsWith('gnomish: round work#')
        } == ['gnomish: round work#0']
        closedPassing(other, newestWith(other, taskId, 'gnomish: round work#0'))
        asked() == 2

        and:
        secondPassIsNoOp(pipeline, other)
    }

    // Scenario: verification/pending-poll / Kill between start and persist re-drives start
    def "#window on another clone, the snapshot delivered by the attempt delivery: the round is re-verified without its agent, and the check starts again"() {
        given:
        taskId = "PEND-D-${System.nanoTime() % 100000}"
        def pipeline = gated([
            gate(server, 'gate', GATE, Duration.ofSeconds(1))
        ], 'work')
        script(server, ['RUNNING', 'RUNNING', 'OK'])

        when: 'the dying instance\'s check started over its delivered snapshot; its round died at the window'
        freshRunOver(pipeline) { PendingKills.killedAt(point, it) }

        then: 'origin\'s register is the snapshot the delivery pushed with it; no pending commit reached origin'
        thrown(RunKills.SimulatedKill)
        def local = commitOf(cloneDir, registerRef(taskId))
        subjectOf(cloneDir, local).startsWith(recorded)
        def snapshot = remote(registerRef(taskId))
        subjectOf(cloneDir, snapshot).startsWith('gnomish: snapshot work#0 ')
        (local == snapshot) == (window == 'W1')

        when: 'a second instance on another clone resumes the task'
        def other = otherClone()
        resume(pipeline, other)

        then: 'no agent: the pickup\'s own start landed one pending commit on the snapshot, and the round closed on it'
        def above = gitOutput(other, 'log', '--reverse', '--format=%H %s', "${snapshot}..${branch()}").readLines()
        above.count { it.substring(41).startsWith('gnomish: snapshot') } == 0
        above.findAll {
            it.substring(41).startsWith('gnomish: pending work#0 ')
        }*.substring(0, 40) == [
            closeAboveIn(other, snapshot)
        ]
        closeAboveIn(other, snapshot) != local
        closedPassing(other, newestWith(other, taskId, 'gnomish: round work#0'))
        asked() == 3

        and:
        secondPassIsNoOp(pipeline, other)

        where:
        window | point | recorded
        'W1' | PendingKills.Point.BEFORE_PENDING_COMMIT | 'gnomish: snapshot work#0 '
        'W1′' | PendingKills.Point.PENDING_COMMIT_PUSH_LOST | 'gnomish: pending work#0 '
    }
}
