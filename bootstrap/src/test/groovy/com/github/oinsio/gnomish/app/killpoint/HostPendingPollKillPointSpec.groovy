package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.*
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import java.time.Instant

/**
 * Task 4.3 of migrate-verify-to-executor-contract (design D21, D22; NFR-R1, NFR-R4), the host
 * medium on the clone that wrote the snapshot: kill windows W0 and W1 (the snapshot registered, the
 * check not started or started and not recorded), W1′ (the pending commit landed here, its push
 * refused), a second stage's first round (the 4.1r note and D21 item 12), and a recorded key of an
 * executor that cannot poll. The pickup is a second instance's real {@code take} over this clone and
 * its bare origin; the windows a second instance on another clone picks up are {@link
 * HostPendingPollOtherCloneSpec}'s.
 */
class HostPendingPollKillPointSpec extends HostPendingPollSpecBase {

    // Scenario: verification/pending-poll / Key lands before polling
    // Scenario: verification/pending-poll / Kill between start and persist re-drives start
    def "#window on this clone: the round is re-verified without its agent, the check starts and its key lands before the poll"() {
        given:
        def check = gate(server)
        def world = world([check], 'build')
        killAfterSnapshot(world.ownGit, world.worktree, TASK, 'build', 0)
        def snapshot = commitOf(world.repoDir, registerRef(TASK))
        script(server, dying + ['RUNNING', 'OK'])
        dying.each { dyingAsk() }
        observed = world.repoDir

        when:
        world.pickUpAfterKill()

        then: 'no agent; the pickup\'s start landed a pending commit on the snapshot before it polled'
        world.agentRounds.call() == 0
        def above = subjectsAbove(world.repoDir, TASK, snapshot)
        above[0].startsWith('gnomish: pending build#0 ')
        def pending = closeAbove(world.repoDir, TASK, snapshot)
        registersAsked == [snapshot, pending]
        asked() == dying.size() + 2

        and: 'the pending commit names no outcome, and the round closed on it'
        at(world.repoDir, pending).attempts() == at(world.repoDir, snapshot).attempts()
        at(world.repoDir, pending).pendingPolls()*.key()*.value() == ['pending-when']
        closedPassing(world.repoDir, closeAbove(world.repoDir, TASK, pending))
        !world.subjects().contains('gnomish: salvage')

        and:
        secondPassIsNoOp(world)

        where:
        window | dying
        'W0' | []
        'W1' | ['RUNNING']
    }

    // Scenario: verification/pending-poll / Kill after persist resumes the poll
    def "W1′ on this clone: the local pending commit whose push was refused is polled as W2, and reaches origin"() {
        given:
        def check = gate(server)
        def world = world([check], 'build')
        def cell = killAfterSnapshot(world.ownGit, world.worktree, TASK, 'build', 0)
        def origin = world.repoDir.resolveSibling('origin.git')
        withOriginGone(origin) {
            landEntries(world, cell, 0, [[check, 'pending-when']])
        }
        def pending = commitOf(world.repoDir, registerRef(TASK))
        assert commitOf(origin, registerRef(TASK)) != pending
        script(server, ['RUNNING', 'OK'])

        when:
        world.pickUpAfterKill()

        then: 'no agent, no start: no other pending commit; the round closed on the local one, now at origin'
        world.agentRounds.call() == 0
        asked() == 2
        subjectsAbove(world.repoDir, TASK, pending).every {
            !it.startsWith('gnomish: pending')
        }
        def close = closeAbove(world.repoDir, TASK, pending)
        closedPassing(world.repoDir, close)
        commitOf(origin, "refs/heads/gnomish/${TASK}") == commitOf(world.repoDir, "gnomish/${TASK}")

        and:
        secondPassIsNoOp(world)
    }

    // Scenario: verification/pending-poll / A pending entry names no outcome
    def "#window on a second stage's first round: the pending commit carries the first stage's pass, and the resume #does"() {
        given: 'the build stage passed; the test stage\'s round registered its snapshot'
        def check = gate(server)
        def world = world([check], 'build', 'test')
        world.roundCommit(TaskState.atStageStart('build').recordPassAndAdvance(AttemptRecords.of(0,
                AttemptRecord.Result.PASSED, Instant.parse('2026-11-12T08:00:00Z'), [], ExecutorUsage.none(), [],
                Stop.none()), new Position.AtStage('test'), ExecutorVersions.none()))
        def cell = killAfterSnapshot(world.ownGit, world.worktree, TASK, 'test', 0)
        def snapshot = commitOf(world.repoDir, registerRef(TASK))
        if (recorded) {
            landEntries(world, cell, 0, [[check, 'pending-when']])
        }
        def below = commitOf(world.repoDir, registerRef(TASK))
        script(server, ['RUNNING', 'OK'])

        when:
        world.pickUpAfterKill()

        then: 'no agent; exactly one pending commit, whichever instance wrote it, carrying the build stage\'s pass'
        world.agentRounds.call() == 0
        asked() == 2
        def pendings = subjectsAbove(world.repoDir, TASK, snapshot).findAll {
            it.startsWith('gnomish: pending test#0 ')
        }
        pendings.size() == 1
        def pending = closeAbove(world.repoDir, TASK, snapshot)
        at(world.repoDir, pending).attempts() == at(world.repoDir, snapshot).attempts()
        at(world.repoDir, pending).attempts()*.result() == [AttemptRecord.Result.PASSED]

        and: 'the round — round zero of its stage — closed on it'
        def close = closeAbove(world.repoDir, TASK, pending)
        closedPassing(world.repoDir, close)
        at(world.repoDir, close).position() == new Position.PipelineEnd()
        below == (recorded ? pending : snapshot)

        and:
        secondPassIsNoOp(world)

        where:
        window | recorded | does
        'W0' | false | 'starts the check and writes it'
        'W2' | true | 'polls its entry and starts nothing'
    }

    def "a recorded key of a check whose executor cannot poll is infrastructure, and the check is not started"() {
        given: 'the round\'s one check is a shell command, which is never pending'
        def world = takeHostWorld(tempDir.resolve('a'), pipeline('build'))
        def cell = killAfterSnapshot(world.ownGit, world.worktree, TASK, 'build', 0)
        landEntries(world, cell, 0, [
            [
                VerifyChecks.command(CHECK),
                'k-1'
            ]
        ])
        def pending = commitOf(world.repoDir, registerRef(TASK))

        when:
        world.pickUpAfterKill()

        then: 'cannot verify — a start would have passed on the snapshot — and no attempt is burned'
        world.agentRounds.call() == 0
        def close = at(world.repoDir, closeAbove(world.repoDir, TASK, pending))
        close.attempts()*.result() == [
            AttemptRecord.Result.CANNOT_VERIFY
        ]
        close.attemptsUsed() == 0
        (close.attempts()[0].stop() as Stop.CannotVerify).reason().toString().contains('never pending')

        and:
        secondPassIsNoOp(world)
    }
}
