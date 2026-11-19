package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.*

import com.github.oinsio.gnomish.adapter.tracker.inmemory.CorrespondenceEntry
import com.github.oinsio.gnomish.app.take.RevocationHandler
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR19, FR20 of migrate-verify-to-executor-contract (design D22; kill windows W0 and W2 of D21 on
 * the clone that wrote the snapshot), the {@code take} path: a git-mode host round killed during its
 * verification, picked up by a second instance as production takes a task over — the dead claim
 * reaped, the task claimed, the host take routes over the same clone and its real bare origin —
 * through {@code TakeResumeRunner.resumeWithoutDecision}, which hands the no-salvage owner's answer
 * to {@code TakeEngineExecution.run}. No stage executor runs (the fake agent's argv log), no attempt
 * is burned, nothing is salvaged, and the round's check passes only on the snapshot's tree.
 *
 * <p>The verify chain runs every check through {@code PendingPoll} since task 4.2f. This world's one
 * check is a {@code shell} command, which never answers {@code pending}, and the entry the fixture
 * lands names no check of the round, so the pickup starts the check; this spec pins what the medium
 * carries to the poll: the entries at the register, under the restored round's identity, read by the
 * engine before its normalization ({@code EngineResumedStateSpec}), and dropped by the round's close.
 * The continuation of a recorded key on the real medium, with a pollable executor, is
 * {@code HostPendingPollKillPointSpec}'s and {@code HostPendingPollOtherCloneSpec}'s.
 */
class HostInterruptedVerificationTakeSpec extends Specification implements TakeKillPointWorlds {

    @TempDir
    Path tempDir

    static final String TASK = TAKE_TASK_ID

    String registered(TakeKillPointWorld world) {
        gitOutput(world.repoDir, 'rev-parse', registerRef(TASK))
    }

    // Scenario: git/git-task-persistence / A resumed host verification runs no agent
    // Scenario: lifecycle/task-branch-contract / An interrupted verification is resumed by its one owner
    def "FR19: a host take killed after its snapshot is re-verified by a second instance, and a second pickup changes nothing"() {
        given: 'the dying instance registered the round\'s snapshot, and its check left residue'
        def world = takeHostWorld(tempDir, pipeline('build'))
        killAfterSnapshot(world.ownGit, world.worktree, TASK, 'build', 0)
        def snapshot = registered(world)

        when:
        world.pickUpAfterKill()

        then: 'no stage executor ran, nothing was salvaged, and the round closed on its snapshot'
        world.agentRounds.call() == 0
        !world.subjects().contains('gnomish: salvage')
        def round = closeAbove(world.repoDir, TASK, snapshot)
        def state = stateAt(world.repoDir, round)
        state.attempts()*.result() == [AttemptRecord.Result.PASSED]
        state.attemptsUsed() == 0

        when: 'the next take of the task'
        def converged = world.fingerprint()
        world.takeAsSecondInstance()

        then:
        world.fingerprint() == converged
        world.agentRounds.call() == 0
    }

    // Kill point W2 on a second stage (the 4.1r note): the pending commit of the stage's first round
    // carries the previous stage's pass, which the resume's normalization drops with the list.
    // Scenario: git/git-task-persistence / A resumed host verification runs no agent
    def "FR19, FR20: a host take killed after a pending commit of a second stage's first round is re-verified without its agent"() {
        given: 'the first stage passed, then the second stage\'s round registered its snapshot and a pending commit'
        def world = takeHostWorld(tempDir, pipeline('build', 'test'))
        world.roundCommit(TaskState.atStageStart('build').recordPassAndAdvance(AttemptRecords.of(0,
                AttemptRecord.Result.PASSED, Instant.parse('2026-11-12T08:00:00Z'), [], ExecutorUsage.none(), [],
                Stop.none()), new Position.AtStage('test'), ExecutorVersions.none()))
        def cell = killAfterSnapshot(world.ownGit, world.worktree, TASK, 'test', 0)
        def entry = landPending(world.ownGit, world.worktree, TASK, cell, 0)
        def pending = registered(world)

        expect: 'the register carries the entry beside the first stage\'s pass, and the normalization alone drops it'
        def recorded = stateAt(world.repoDir, pending)
        recorded.pendingPolls() == [entry]
        recorded.attempts()*.result() == [AttemptRecord.Result.PASSED]
        recorded.startOfStage().pendingPolls().isEmpty()

        when:
        world.pickUpAfterKill()

        then: 'no stage executor ran and the round — round zero of its stage — closed on the pending commit'
        world.agentRounds.call() == 0
        !world.subjects().contains('gnomish: salvage')
        def round = closeAbove(world.repoDir, TASK, pending)
        def closed = stateAt(world.repoDir, round)
        closed.attempts()*.round() == [0]
        closed.attempts()*.result() == [AttemptRecord.Result.PASSED]
        closed.attemptsUsed() == 0

        and: 'the round close drops the pending entries'
        closed.pendingPolls().isEmpty()
    }

    // Scenario: git/git-task-persistence / A revocation during a verification lands no salvage commit
    def "FR19: a host revocation during a verification above its #registeredKind lands no salvage commit, and the next pickup re-verifies"() {
        given:
        def world = takeHostWorld(tempDir, pipeline('build'))
        def cell = killAfterSnapshot(world.ownGit, world.worktree, TASK, 'build', 0)
        if (withPending) {
            landPending(world.ownGit, world.worktree, TASK, cell, 0)
        }
        def above = registered(world)

        when: 'the dying instance\'s revocation stop runs'
        new RevocationHandler(world.tracker, world.ownGit.worktrees(), world.ownGit.branches())
                .handle(world.ref, TASK, stateAt(world.repoDir, above), world.worktree, 'claim taken over')

        then: 'no salvage commit landed; the register still names that commit, carried to origin'
        !world.subjects().contains('gnomish: salvage')
        registered(world) == above
        gitOutput(world.repoDir.resolveSibling('origin.git'), 'rev-parse', registerRef(TASK)) == above
        !Files.exists(world.worktree.resolve(RESIDUE))

        and: 'the stop note names the discard'
        world.trackerHarness.thread(world.ref).any {
            it.kind() == CorrespondenceEntry.Kind.NOTE && it.text().contains('no salvage commit landed')
        }

        when: 'the next pickup: the task is returned to the queue and claimed again, its tenure recorded first'
        world.returnedAndReclaimed()
        world.pickup()

        then: 'it re-verifies the round without running its stage executor'
        world.agentRounds.call() == 0
        stateAt(world.repoDir, closeAbove(world.repoDir, TASK, above)).attempts()*.result() == [AttemptRecord.Result.PASSED]

        where:
        registeredKind | withPending
        'snapshot' | false
        'pending commit' | true
    }
}
