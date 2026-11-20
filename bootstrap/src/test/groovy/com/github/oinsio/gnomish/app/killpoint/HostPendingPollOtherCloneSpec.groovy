package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.*
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import java.nio.file.Files
import java.time.Instant

/**
 * Task 4.3 of migrate-verify-to-executor-contract (design D21, D22; NFR-R1, NFR-R4), the host
 * medium picked up by a second instance on another clone of the same bare origin: W0 and W1, whose
 * snapshot never left the dying clone, and W1′, whose pending commit did not either, are an
 * ordinary resume there (the stage executor re-runs, no attempt burned); W2 and W4 read the same on
 * every clone and are continued with no {@code start}; after the close nothing is polled. W3, whose
 * continuation rotates the key (task 4.6), is {@link HostPendingRotationSpec}'s.
 */
class HostPendingPollOtherCloneSpec extends HostPendingPollSpecBase {

    def "#window on another clone: origin's register is the previous closing commit, so the stage executor re-runs and no attempt is burned"() {
        given:
        def check = gate(server)
        def world = world([check], 'build')
        def cell = killAfterSnapshot(world.ownGit, world.worktree, TASK, 'build', 0)
        def before = commitOf(world.repoDir.resolveSibling('origin.git'), registerRef(TASK))
        if (window == 'W1′') {
            withOriginGone(world.repoDir.resolveSibling('origin.git')) {
                landEntries(world, cell, 0, [[check, 'pending-when']])
            }
        }
        script(server, dying + ['RUNNING', 'OK'])
        dying.each { dyingAsk() }
        def other = world.secondOnAnotherClone.call(tempDir.resolve('b'))

        when:
        world.pickUpAfterKill()

        then: 'the agent ran once; its round started the check, landed its own pending commit and closed'
        world.agentRounds.call() == 1
        def above = subjectsAbove(other, TASK, before)
        above.count { it.startsWith('gnomish: pending build#0 ') } == 1
        asked() == dying.size() + 2
        closedPassing(other, newestWith(other, TASK, 'gnomish: round build#0'))

        and:
        secondPassIsNoOp(world)

        where:
        window | dying
        'W0' | []
        'W1' | ['RUNNING']
        'W1′' | ['RUNNING']
    }

    // Scenario: verification/pending-poll / Kill after persist resumes the poll
    // Scenario: verification/pending-poll / Kill after the terminal poll re-polls the persisted key
    // Scenario: verification/pending-poll / A pending entry names no outcome
    // Scenario: verification/pending-poll / Second recovery is a no-op
    // Scenario: lifecycle/task-branch-contract / The round close drops the pending entries
    def "#window on another clone: the recorded key is polled #times time(s), nothing is started, and the round closes on the pending commit"() {
        given:
        def check = gate(server)
        def world = world([check], 'build')
        def cell = killAfterSnapshot(world.ownGit, world.worktree, TASK, 'build', 0)
        def snapshot = commitOf(world.repoDir, registerRef(TASK))
        landEntries(world, cell, 0, [[check, key]])
        def pending = commitOf(world.repoDir, registerRef(TASK))
        script(server, gate)
        dying.times { dyingAsk() }
        def other = world.secondOnAnotherClone.call(tempDir.resolve('b'))

        when:
        world.pickUpAfterKill()

        then: 'no agent and no start: no pending commit but the recorded one, which names no outcome'
        world.agentRounds.call() == 0
        asked() == dying + times
        subjectsAbove(other, TASK, pending).every {
            !it.startsWith('gnomish: pending')
        }
        at(other, pending).attempts() == at(other, snapshot).attempts()

        and: 'the round closed on the pending commit, dropping its entry'
        closedPassing(other, closeAbove(other, TASK, pending))

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
        def cell = killAfterSnapshot(world.ownGit, world.worktree, TASK, 'build', 0)
        landEntries(world, cell, 0, [
            [first, 'pending-when'],
            [second, 'pending-when']
        ])
        def pending = commitOf(world.repoDir, registerRef(TASK))
        script(server, ['OK'], '/first')
        script(server, ['RUNNING', 'OK'], '/second')
        def other = world.secondOnAnotherClone.call(tempDir.resolve('b'))

        when:
        world.pickUpAfterKill()

        then:
        world.agentRounds.call() == 0
        asked('/first') == 1
        asked('/second') == 2
        subjectsAbove(other, TASK, pending).every {
            !it.startsWith('gnomish: pending')
        }
        closedPassing(other, closeAbove(other, TASK, pending))
    }

    // Scenario: verification/pending-poll / An entry of another round is not continued
    def "an entry under another round's token is not polled: its check starts, and the write drops the stale entry"() {
        given: 'the first check holds only an entry another round left; the second, the round\'s own'
        def first = gate(server, 'first', '/first')
        def second = gate(server, 'second', '/second')
        def world = world([first, second], 'build')
        def cell = killAfterSnapshot(world.ownGit, world.worktree, TASK, 'build', 0)
        landEntries(world, cell, 0, [
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
        def other = world.secondOnAnotherClone.call(tempDir.resolve('b'))

        when:
        world.pickUpAfterKill()

        then: 'the first check\'s start landed a pending commit carrying the round\'s own entries only'
        world.agentRounds.call() == 0
        def fresh = closeAbove(other, TASK, recorded)
        subjectOf(other, fresh).startsWith('gnomish: pending build#0 ')
        at(other, fresh).pendingPolls()*.key()*.value() == ['k-own', 'pending-when']
        at(other, fresh).pendingPolls()*.token().every { it == cell.opened() }

        and: 'the second check\'s own entry was polled, never started'
        asked('/first') == 2
        asked('/second') == 1
        closedPassing(other, closeAbove(other, TASK, fresh))
    }

    // Scenario: lifecycle/task-branch-contract / The round close drops the pending entries
    def "after the round's closing commit, another clone's pickup polls nothing, runs no agent and writes no pending commit"() {
        given: 'the dying instance closed the round over its pending commit, then died before any tracker write'
        def check = gate(server)
        def world = world([check], 'build')
        def cell = killAfterSnapshot(world.ownGit, world.worktree, TASK, 'build', 0)
        landEntries(world, cell, 0, [[check, 'pending-when']])
        def pending = commitOf(world.repoDir, registerRef(TASK))
        Files.delete(world.worktree.resolve(RESIDUE))
        def record = AttemptRecords.identified(TASK, 'build', cell.opened(), 0, AttemptRecord.Result.PASSED,
                Instant.parse('2026-11-12T09:00:00Z'), [], ExecutorUsage.none(), [], Stop.none())
        world.ownGit.store().attemptPersistence(world.worktree, TASK, cell).persist(TASK, at(world.repoDir, pending)
                .withPendingPolls([]).recordPassAndAdvance(record, new Position.PipelineEnd(), ExecutorVersions.none()),
                new ToolTrace(new AttemptKey(TASK, 'build', 0), []))
        def close = commitOf(world.repoDir, registerRef(TASK))
        def other = world.secondOnAnotherClone.call(tempDir.resolve('b'))

        when:
        world.pickUpAfterKill()

        then:
        asked() == 0
        world.agentRounds.call() == 0
        subjectsAbove(other, TASK, close).every {
            !it.startsWith('gnomish: pending') && !it.startsWith('gnomish: round')
        }
        closedPassing(other, close)

        and:
        secondPassIsNoOp(world)
    }
}
