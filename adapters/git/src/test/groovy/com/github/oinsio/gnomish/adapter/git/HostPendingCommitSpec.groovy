package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.ClosedRound
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR20 of migrate-verify-to-executor-contract (design D21): the host end of the pending commit
 * on the real medium — a registered task's worktree, a completed round snapshotted by the production
 * host-git decoration over the run's cell, and the production persistence over the same cell. The
 * commit is built from objects in the clone's {@code .git}: exactly {@code state.json}, on the round's
 * last registered commit, landed with its register under a compare-and-swap on that commit.
 */
class HostPendingCommitSpec extends Specification implements HostSnapshotRound {

    @TempDir
    Path tempDir

    String snapshot

    def setup() {
        setupHostRound()
        runRound(completed()) { gnomeWorks() }
        snapshot = tip()
    }

    private String parentsOf(String commit) {
        gitOutput(cloneDir, 'rev-list', '--parents', '-n', '1', commit).split(' ').drop(1).join(' ')
    }

    // Scenario: git/git-task-persistence / A pending commit sits on the round's last registered commit
    def "FR20: the round's first pending commit sits on its snapshot, changes only state.json, and lands with its register"() {
        when:
        persistPending('run-1')

        then: 'branch and register name the pending commit, whose only parent is the snapshot'
        def pending = tip()
        pending != snapshot
        registeredId(cloneDir, TASK) == pending
        parentsOf(pending) == snapshot
        changedIn(pending) == ['.gnomish-task/state.json']

        and: 'its subject names the stage, the round and its token; its state carries the entry'
        subjectOf(cloneDir, pending) == "gnomish: pending implement#${ROUND} ${cell.opened().commit()}"
        fileAt(pending, '.gnomish-task/state.json').contains('run-1')

        and: 'the cell moved its last registered commit; the checks still judge the snapshot'
        cell.closed() == new ClosedRound(cell.opened(), snapshot, pending, RoundClose.VERIFIABLE)
        cell.lastRegistered() == Optional.of(pending)
    }

    // Scenario: git/git-task-persistence / A second pending check stacks on the first pending commit
    def "FR20: a second pending check's commit stacks on the first one and carries both entries"() {
        given:
        persistPending('run-1')
        def first = tip()

        when:
        persistPending('run-1', 'run-2')

        then:
        def second = tip()
        registeredId(cloneDir, TASK) == second
        parentsOf(second) == first
        changedIn(second) == ['.gnomish-task/state.json']
        def state = fileAt(second, '.gnomish-task/state.json')
        state.contains('run-1') && state.contains('run-2')
        cell.closed().registered() == second
        cell.closed().attemptCommit() == snapshot
    }

    // Scenario: git/git-task-persistence / A key rotation stacks a pending commit
    def "FR10: a rotated key's pending commit stacks on the previous pending commit, its register moved with it"() {
        given: 'the check\'s first key landed'
        persistPending('run-1')
        def previous = tip()

        when: 'a poll of it returned another key, written over the same entry'
        persistPending('run-2')

        then:
        def rotated = tip()
        registeredId(cloneDir, TASK) == rotated
        parentsOf(rotated) == previous
        changedIn(rotated) == ['.gnomish-task/state.json']
        def state = fileAt(rotated, '.gnomish-task/state.json')
        state.contains('run-2') && !state.contains('run-1')
        cell.closed().registered() == rotated
        cell.closed().attemptCommit() == snapshot
    }

    // Scenario: git/git-task-persistence / A pending commit is built from the registered commit alone
    def "FR20: what a pending check staged in the worktree does not enter the pending commit's tree"() {
        given: 'a check still running in the worktree has staged a file and edited state.json'
        Files.writeString(worktree().resolve('check-report.txt'), 'staged by the check')
        gitOutput(worktree(), 'add', 'check-report.txt')
        Files.writeString(worktree().resolve('.gnomish-task/state.json'), '{"forged":true}')

        when:
        persistPending('run-1')

        then: 'the tree is the snapshot\'s with state.json alone replaced, by the factory\'s own content'
        def pending = tip()
        parentsOf(pending) == snapshot
        gitOutput(cloneDir, 'diff-tree', '-r', '--name-only', '--no-commit-id', snapshot, pending) == '.gnomish-task/state.json'
        fileAt(pending, '.gnomish-task/state.json').contains('run-1')
        !fileAt(pending, '.gnomish-task/state.json').contains('forged')
    }

    // Scenario: git/git-task-persistence / A pending commit is built from the registered commit alone
    def "FR20: on the host the pending commit's ref transaction refuses a branch a pending check moved"() {
        given: 'a check committed in the worktree, moving the task branch above the snapshot'
        commit(worktree(), 'check.txt', 'committed by the check')
        def moved = tip()

        when:
        persistPending('run-1')

        then: 'the write throws and moves nothing: the branch stays the check\'s, the register the snapshot'
        thrown(GitPersistFailedException)
        tip() == moved
        registeredId(cloneDir, TASK) == snapshot
        cell.closed().registered() == snapshot
    }

    // D21, kill windows W1′ and W2: a pending commit is pushed with its register, so every clone
    // reads it alike after its fetch
    def "FR20: the host pending commit reaches origin with its register in one push"() {
        given:
        def origin = initBareRepo(tempDir, 'origin')
        addRemote(cloneDir, 'origin', origin.toString())

        when:
        persistPending('run-1')

        then:
        def pending = tip()
        gitOutput(origin, 'rev-parse', 'refs/heads/gnomish/PROJ-1') == pending
        gitOutput(origin, 'rev-parse', 'refs/gnomish-factory/PROJ-1') == pending
    }

    // D21, "A round with no registered snapshot writes no pending commit"
    def "FR20: a round whose snapshot was declined refuses the pending write before anything is written"() {
        given: 'a round with no snapshot in the cell'
        setupFreshRoundWithoutSnapshot()
        def registered = registeredId(cloneDir, TASK)

        when:
        persistPending('run-1')

        then:
        def refused = thrown(GitPersistFailedException)
        refused.message.contains('no registered snapshot')
        registeredId(cloneDir, TASK) == registered
        tip() == registered
    }

    /** The next round, opened over the current tip, its stage executor not completed: no snapshot. */
    private void setupFreshRoundWithoutSnapshot() {
        persistRound()
        cell.open(com.github.oinsio.gnomish.domain.engine.RoundToken.of(tip()))
    }
}
