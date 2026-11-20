package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.*
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*

import java.nio.file.Files

/**
 * Task 5.3 of migrate-verify-to-executor-contract (FR3, FR11, NFR-R1; design D11 as amended
 * 2026-11-14, D14 as amended 2026-11-13, D21): the container kill-point matrix of task 4.3b with a
 * pollable {@code program} verdict check on the clone that wrote it, without a daemon ({@link
 * ContainerPendingKill}, {@link ContainerProgramChecks}). Each request of the check — a {@code start},
 * a poll, a recorded key's adoption with its poll — runs in a box of its own materialized at the
 * round's attempt commit and released at the request's end; the program sees {@code HEAD} equal to
 * the {@code ClosedRound.attemptCommit} of the round under verification on every one of them (the
 * identity of the D9 rows), and keeps its run outside every box. W2 adopts then polls, W3 rotates the
 * key to one distinguishable from the first, W4 polls the ended run again; none sends a {@code start},
 * and each ends with a no-op second recovery. Another clone's pickup is the Docker layer's ({@code
 * PendingPollProgramOtherCloneContainerE2ESpec}).
 */
class ContainerProgramPollSpec extends ContainerPendingPollSpecBase {

    ContainerProgramChecks programs

    TakeKillPointWorld programWorld() {
        programs = new ContainerProgramChecks(Files.createDirectories(tempDir.resolve('program')))
        def world = world(programs.law())
        kill.programs = programs
        world
    }

    @Override
    Map footprint(TakeKillPointWorld world) {
        super.footprint(world) + [requests: programs.requests().size(), boxes: programs.grants.size()]
    }

    /** Every request stood on {@code commit}, each in a box of its own built for the program's grant and released. */
    boolean eachInItsOwnBoxAt(String commit) {
        def requests = programs.requests()
        requests*.get(2).every {
            it == commit
        } && requests*.get(3).unique().size() == requests.size() &&
        programs.grants == [['api.judge.example']] * requests.size() && programs.released == requests.size()
    }

    // Scenario: verification/verdict-executors / Two requests of one check see one commit in two boxes
    def "W0: the check's start and its poll run in two boxes, both at the round's attempt commit"() {
        given:
        def world = programWorld()
        def cell = kill.killAfterSnapshot('build', 0)
        def snapshot = commitOf(world.repoDir, registerRef(TASK))
        programs.script(['pending:k-1', 'verdict'])

        when:
        kill.pickUp()

        then: 'a start, then a poll of its key, each in its own box at the attempt commit'
        kill.agentRounds == 0
        programs.requests()*.take(2) == [
            ['start', '-'],
            ['poll', 'k-1']
        ]
        eachInItsOwnBoxAt(cell.closed().attemptCommit())

        and: 'the start\'s key landed before the poll, and the round closed on it'
        def pending = closeAbove(world.repoDir, TASK, snapshot)
        at(world.repoDir, pending).pendingPolls()*.key()*.value() == ['k-1']
        closedPassing(world.repoDir, closeAbove(world.repoDir, TASK, pending))

        and:
        secondPassIsNoOp(world)
    }

    // Scenario: verification/verdict-executors / A resumed poll runs in a box at the same commit
    // Scenario: verification/pending-poll / Kill after persist resumes the poll
    // Scenario: verification/pending-poll / Kill after the terminal poll re-polls the persisted key
    def "#window: the recorded key is adopted and polled #times time(s) in new boxes at the attempt commit, and nothing starts"() {
        given:
        def world = programWorld()
        def cell = kill.killAfterSnapshot('build', 0)
        kill.landEntries(cell, 0, [[programs.check(), 'k-1']])
        def pending = commitOf(world.repoDir, registerRef(TASK))
        programs.script(answers)

        when:
        kill.pickUp()

        then: 'polls only, of the recorded key: no start, no pending commit but the recorded one'
        kill.agentRounds == 0
        programs.requests()*.take(2) == [['poll', 'k-1']] * times
        eachInItsOwnBoxAt(cell.closed().attemptCommit())
        subjectsAbove(world.repoDir, TASK, pending).every {
            !it.startsWith('gnomish: pending')
        }

        and: 'the round closed on the recorded pending commit'
        closedPassing(world.repoDir, closeAbove(world.repoDir, TASK, pending))
        secondPassIsNoOp(world)

        where:
        window | answers | times
        'W2' | ['pending:k-1', 'verdict'] | 2
        'W4' | ['verdict'] | 1
    }

    // Scenario: verification/pending-poll / Kill before the new key lands polls the previous key
    // Scenario: verification/pending-poll / New key lands before the next poll
    def "W3: the previous key is polled, the rotated key lands on the recorded commit, and the next poll sends it"() {
        given: 'the dying instance recorded k-1; its poll that rotated the key was never recorded'
        def world = programWorld()
        def cell = kill.killAfterSnapshot('build', 0)
        kill.landEntries(cell, 0, [[programs.check(), 'k-1']])
        def recorded = commitOf(world.repoDir, registerRef(TASK))
        programs.script(['pending:k-2', 'verdict'])

        when:
        kill.pickUp()

        then: 'no start: the recorded key, then the rotated one, each in its own box at the attempt commit'
        kill.agentRounds == 0
        programs.requests()*.take(2) == [
            ['poll', 'k-1'],
            ['poll', 'k-2']
        ]
        eachInItsOwnBoxAt(cell.closed().attemptCommit())

        and: 'one rotated pending commit on the recorded one, naming the new key'
        subjectsAbove(world.repoDir, TASK, recorded).count {
            it.startsWith('gnomish: pending')
        } == 1
        def rotated = closeAbove(world.repoDir, TASK, recorded)
        at(world.repoDir, rotated).pendingPolls()*.key()*.value() == ['k-2']
        closedPassing(world.repoDir, closeAbove(world.repoDir, TASK, rotated))
        secondPassIsNoOp(world)
    }
}
