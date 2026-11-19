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
 * Task 4.3b of migrate-verify-to-executor-contract (design D21, D22; NFR-R1, NFR-R4), the container
 * medium on the clone that wrote the snapshot, without a daemon ({@link ContainerPendingKill}): kill
 * windows W0 and W1 (the snapshot registered, the check not started or started and not recorded),
 * W1′ (the pending commit landed here, its push refused), a second stage's first round (the 4.1r
 * note and D21 item 12), and a recorded key of an executor that cannot poll. The recorded windows
 * W2–W4 and the close are {@link ContainerPendingPollRecordedSpec}'s; another clone's pickup is the
 * Docker layer's ({@code PendingPollOtherCloneContainerE2ESpec}). The host twin is {@link
 * HostPendingPollKillPointSpec}.
 */
class ContainerPendingPollKillPointSpec extends ContainerPendingPollSpecBase {

    // Scenario: verification/pending-poll / Key lands before polling
    // Scenario: verification/pending-poll / Kill between start and persist re-drives start
    def "#window on this clone: the round is re-verified without its agent, the check starts and its key lands before the poll"() {
        given:
        def check = gate(server)
        def world = world([check], 'build')
        kill.killAfterSnapshot('build', 0)
        def snapshot = commitOf(world.repoDir, registerRef(TASK))
        script(server, dying + ['RUNNING', 'OK'])
        dying.each { dyingAsk() }
        observed = world.repoDir

        when:
        kill.pickUp()

        then: 'no agent; the pickup\'s start landed a pending commit on the snapshot before it polled'
        kill.agentRounds == 0
        subjectsAbove(world.repoDir, TASK, snapshot)[0].startsWith('gnomish: pending build#0 ')
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
        def cell = kill.killAfterSnapshot('build', 0)
        def origin = world.repoDir.resolveSibling('origin.git')
        withOriginGone(origin) {
            kill.landEntries(cell, 0, [[check, 'pending-when']])
        }
        def pending = commitOf(world.repoDir, registerRef(TASK))
        assert commitOf(origin, registerRef(TASK)) != pending
        script(server, ['RUNNING', 'OK'])

        when:
        kill.pickUp()

        then: 'no agent, no start: no other pending commit; the round closed on the local one, now at origin'
        kill.agentRounds == 0
        asked() == 2
        subjectsAbove(world.repoDir, TASK, pending).every {
            !it.startsWith('gnomish: pending')
        }
        closedPassing(world.repoDir, closeAbove(world.repoDir, TASK, pending))
        commitOf(origin, "refs/heads/gnomish/${TASK}") == commitOf(world.repoDir, "gnomish/${TASK}")
        commitOf(origin, registerRef(TASK)) == commitOf(world.repoDir, registerRef(TASK))

        and:
        secondPassIsNoOp(world)
    }

    // Scenario: verification/pending-poll / A pending entry names no outcome
    def "#window on a second stage's first round: the pending commit carries the first stage's pass, and the resume #does"() {
        given: 'the build stage passed in its box; the test stage\'s round registered its snapshot'
        def check = gate(server)
        def world = world([check], 'build', 'test')
        def build = kill.killAfterSnapshot('build', 0)
        def passed = AttemptRecords.identified(TASK, 'build', build.opened(), 0, AttemptRecord.Result.PASSED,
                Instant.parse('2026-11-12T08:00:00Z'), [], ExecutorUsage.none(), [], Stop.none())
        kill.close(build, TaskState.atStageStart('build').recordPassAndAdvance(passed, new Position.AtStage('test'),
                ExecutorVersions.none()), 0, 'build')
        def cell = kill.killAfterSnapshot('test', 0)
        def snapshot = commitOf(world.repoDir, registerRef(TASK))
        if (recorded) {
            kill.landEntries(cell, 0, [[check, 'pending-when']])
        }
        def below = commitOf(world.repoDir, registerRef(TASK))
        script(server, ['RUNNING', 'OK'])

        when:
        kill.pickUp()

        then: 'no agent; exactly one pending commit, whichever instance wrote it, carrying the build stage\'s pass'
        kill.agentRounds == 0
        asked() == 2
        subjectsAbove(world.repoDir, TASK, snapshot).count {
            it.startsWith('gnomish: pending test#0 ')
        } == 1
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
        def world = world(pipeline('build'))
        def cell = kill.killAfterSnapshot('build', 0)
        kill.landEntries(cell, 0, [
            [
                VerifyChecks.command(CHECK),
                'k-1'
            ]
        ])
        def pending = commitOf(world.repoDir, registerRef(TASK))

        when:
        kill.pickUp()

        then: 'cannot verify — a start would have run the command — and no attempt is burned'
        kill.agentRounds == 0
        def close = at(world.repoDir, closeAbove(world.repoDir, TASK, pending))
        close.attempts()*.result() == [
            AttemptRecord.Result.CANNOT_VERIFY
        ]
        close.attemptsUsed() == 0
        (close.attempts()[0].stop() as Stop.CannotVerify).reason().toString().contains('never pending')
    }
}
