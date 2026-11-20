package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.*
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import java.time.Instant

/**
 * Task 4.3b of migrate-verify-to-executor-contract (design D21, D22; NFR-R1, NFR-R4), the container
 * medium's recorded windows on the clone that wrote them, without a daemon ({@link
 * ContainerPendingKill}): W2 and W4 are continued with no {@code start} — every entry of the round,
 * and only the round's own — and after the round's closing commit nothing is polled; W3, whose
 * continuation rotates the key (task 4.6), is {@link ContainerPendingRotationSpec}'s. A pending commit is
 * pushed with its register, so these rows read the same on every clone; another clone's pickup is
 * the Docker layer's ({@code PendingPollRecordedOtherCloneContainerE2ESpec}). The host twin is {@link
 * HostPendingPollOtherCloneSpec}.
 */
class ContainerPendingPollRecordedSpec extends ContainerPendingPollSpecBase {

    // Scenario: verification/pending-poll / Kill after persist resumes the poll
    // Scenario: verification/pending-poll / Kill after the terminal poll re-polls the persisted key
    // Scenario: verification/pending-poll / A pending entry names no outcome
    // Scenario: verification/pending-poll / Second recovery is a no-op
    // Scenario: lifecycle/task-branch-contract / The round close drops the pending entries
    def "#window: the recorded key is polled #times time(s), nothing is started, and the round closes on the pending commit"() {
        given:
        def check = gate(server)
        def world = world([check], 'build')
        def cell = kill.killAfterSnapshot('build', 0)
        def snapshot = commitOf(world.repoDir, registerRef(TASK))
        kill.landEntries(cell, 0, [[check, key]])
        def pending = commitOf(world.repoDir, registerRef(TASK))
        script(server, gate)
        dying.times { dyingAsk() }

        when:
        kill.pickUp()

        then: 'no agent and no start: no pending commit but the recorded one, which names no outcome'
        kill.agentRounds == 0
        asked() == dying + times
        subjectsAbove(world.repoDir, TASK, pending).every {
            !it.startsWith('gnomish: pending')
        }
        at(world.repoDir, pending).attempts() == at(world.repoDir, snapshot).attempts()

        and: 'the round closed on the pending commit, dropping its entry, and origin carries it'
        closedPassing(world.repoDir, closeAbove(world.repoDir, TASK, pending))
        commitOf(world.repoDir.resolveSibling('origin.git'), registerRef(TASK)) == commitOf(world.repoDir, registerRef(TASK))

        and:
        secondPassIsNoOp(world)

        where:
        window | key | dying | gate | times
        'W2' | 'pending-when' | 0 | ['RUNNING', 'OK'] | 2
        'W4' | 'pending-when' | 1 | ['OK'] | 1
    }

    // Scenario: verification/pending-poll / Every pending check of the round is continued
    def "W2 with two pending checks, the first one ended: both recorded keys are polled and neither check starts"() {
        given:
        def first = gate(server, 'first', '/first')
        def second = gate(server, 'second', '/second')
        def world = world([first, second], 'build')
        def cell = kill.killAfterSnapshot('build', 0)
        kill.landEntries(cell, 0, [
            [first, 'pending-when'],
            [second, 'pending-when']
        ])
        def pending = commitOf(world.repoDir, registerRef(TASK))
        script(server, ['OK'], '/first')
        script(server, ['RUNNING', 'OK'], '/second')

        when:
        kill.pickUp()

        then:
        kill.agentRounds == 0
        asked('/first') == 1
        asked('/second') == 2
        subjectsAbove(world.repoDir, TASK, pending).every {
            !it.startsWith('gnomish: pending')
        }
        closedPassing(world.repoDir, closeAbove(world.repoDir, TASK, pending))
    }

    // Scenario: verification/pending-poll / An entry of another round is not continued
    def "an entry under another round's token is not polled: its check starts, and the write drops the stale entry"() {
        given: 'the first check holds only an entry another round left; the second, the round\'s own'
        def first = gate(server, 'first', '/first')
        def second = gate(server, 'second', '/second')
        def world = world([first, second], 'build')
        def cell = kill.killAfterSnapshot('build', 0)
        kill.landEntries(cell, 0, [
            [
                first,
                'k-stale',
                RoundToken.of('f' * 40)
            ],
            [second, 'k-own']
        ])
        def recorded = commitOf(world.repoDir, registerRef(TASK))
        script(server, ['RUNNING', 'OK'], '/first')
        script(server, ['OK'], '/second')

        when:
        kill.pickUp()

        then: 'the first check\'s start landed a pending commit carrying the round\'s own entries only'
        kill.agentRounds == 0
        def fresh = closeAbove(world.repoDir, TASK, recorded)
        subjectOf(world.repoDir, fresh).startsWith('gnomish: pending build#0 ')
        at(world.repoDir, fresh).pendingPolls()*.key()*.value() == ['k-own', 'pending-when']
        at(world.repoDir, fresh).pendingPolls()*.token().every {
            it == cell.opened()
        }

        and: 'the second check\'s own entry was polled, never started'
        asked('/first') == 2
        asked('/second') == 1
        closedPassing(world.repoDir, closeAbove(world.repoDir, TASK, fresh))
    }

    // Scenario: lifecycle/task-branch-contract / The round close drops the pending entries
    def "after the round's closing commit, the pickup polls nothing, runs no agent and writes no pending commit"() {
        given: 'the dying instance closed the round over its pending commit in its box, then died'
        def check = gate(server)
        def world = world([check], 'build')
        def cell = kill.killAfterSnapshot('build', 0)
        kill.landEntries(cell, 0, [[check, 'pending-when']])
        def pending = commitOf(world.repoDir, registerRef(TASK))
        def record = AttemptRecords.identified(TASK, 'build', cell.opened(), 0, AttemptRecord.Result.PASSED,
                Instant.parse('2026-11-12T09:00:00Z'), [], ExecutorUsage.none(), [], Stop.none())
        kill.close(cell, at(world.repoDir, pending).withPendingPolls([])
        .recordPassAndAdvance(record, new Position.PipelineEnd(), ExecutorVersions.none()), 0, 'build')
        def close = commitOf(world.repoDir, registerRef(TASK))
        assert closeAbove(world.repoDir, TASK, pending) == close

        when:
        kill.pickUp()

        then:
        asked() == 0
        kill.agentRounds == 0
        subjectsAbove(world.repoDir, TASK, close).every {
            !it.startsWith('gnomish: pending') && !it.startsWith('gnomish: round')
        }
        closedPassing(world.repoDir, close)

        and:
        secondPassIsNoOp(world)
    }
}
