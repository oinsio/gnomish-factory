package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.HostVerificationKill.registerRef
import static com.github.oinsio.gnomish.app.HostVerificationKill.stateAt
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*

import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Timeout
/**
 * Task 4.3b of migrate-verify-to-executor-contract (design D21, D22; NFR-R1, NFR-R4), the container
 * medium's recorded windows picked up on another clone ({@link PendingPollOtherCloneE2ESpecBase}): a
 * pending commit is pushed with its register, so W2 (after the pending commit) and W4 (after a
 * terminal poll, before the round's close) read the same on every clone — the recorded key is
 * polled, nothing is started, and the round closes on the pending commit. Docker- and
 * guard-image-gated.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class PendingPollRecordedOtherCloneContainerE2ESpec extends PendingPollOtherCloneE2ESpecBase {

    // Scenario: verification/pending-poll / Kill after persist resumes the poll
    // Scenario: verification/pending-poll / Kill after the terminal poll re-polls the persisted key
    // Scenario: verification/pending-poll / A pending entry names no outcome
    // Scenario: verification/pending-poll / Second recovery is a no-op
    // Scenario: lifecycle/task-branch-contract / The round close drops the pending entries
    def "#window on another clone: the recorded key is polled, nothing is started, and the round closes on the pending commit"() {
        given:
        taskId = "PEND-B-${System.nanoTime() % 100000}"
        def pipeline = gated([
            gate(server, 'gate', GATE, Duration.ofSeconds(1))
        ], 'work')
        script(server, ['RUNNING', 'OK'])

        when:
        freshRunOver(pipeline) { PendingKills.killedAt(point, it) }

        then: 'the pending commit reached origin with its register'
        thrown(RunKills.SimulatedKill)
        def pending = commitOf(cloneDir, registerRef(taskId))
        subjectOf(cloneDir, pending).startsWith('gnomish: pending work#0 ')
        remote(registerRef(taskId)) == pending

        when: 'a second instance on another clone resumes the task'
        def other = otherClone()
        resume(pipeline, other)

        then: 'no agent and no start: nothing but the round\'s close above the recorded pending commit'
        def above = gitOutput(other, 'log', '--reverse', '--format=%s', "${pending}..${branch()}").readLines()
        above.first() == 'gnomish: round work#0'
        above.every {
            !it.startsWith('gnomish: pending') && !it.startsWith('gnomish: snapshot')
        }
        stateAt(other, pending).attempts() == stateAt(other, "${pending}^").attempts()
        closedPassing(other, newestWith(other, taskId, 'gnomish: round work#0'))
        asked() == dyingAsks + 1

        and:
        secondPassIsNoOp(pipeline, other)

        where:
        window | point | dyingAsks
        'W2' | PendingKills.Point.AFTER_PENDING_COMMIT | 1
        'W4' | PendingKills.Point.AFTER_TERMINAL_POLL | 2
    }
}
