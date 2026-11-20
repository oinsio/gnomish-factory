package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.RoundStates
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR20 of migrate-verify-to-executor-contract (design D21): the container end of the pending
 * commit on local repositories with container semantics ({@link LocalBoxEnvironment}) — the in-box
 * commit through a private index, its harvest, the parent-check, the read-back, the tree check and
 * the admission with its register.
 */
class InBoxPendingCommitSpec extends Specification implements InBoxPendingRound {

    @TempDir
    Path tempDir

    def setup() {
        setupPendingRound()
    }

    // Scenario: git/git-task-persistence / A pending commit sits on the round's last registered commit
    def "FR20: the round's first pending commit sits on the snapshot, carries only state.json, and is admitted with its register"() {
        when:
        persistence().persistPending(TASK, pendingState('run-1'))

        then:
        def pending = tip()
        registered() == pending
        parentsOf(pending) == snapshot
        changedIn(pending) == [STATE]
        gitOutput(cloneDir, 'log', '-1', '--format=%s', pending) == "gnomish: pending implement#1 ${rounds.opened().commit()}"
        gitOutput(cloneDir, 'show', pending + ':' + STATE).contains('run-1')
        rounds.closed().registered() == pending
        rounds.closed().attemptCommit() == snapshot

        and: 'the box follows it: its HEAD is the same commit, its index and working tree keep the old state.json'
        gitOutput(box.workingCopy, 'rev-parse', 'HEAD') == pending
        gitOutput(box.workingCopy, 'rev-parse', ':' + STATE) == gitOutput(cloneDir, 'rev-parse', snapshot + ':' + STATE)
        Files.readString(box.workingCopy.resolve(STATE)) == '{"stateVersion":1}'
    }

    // Scenario: git/git-task-persistence / A second pending check stacks on the first pending commit
    def "FR20: a second pending check's commit stacks on the first in the box too"() {
        given:
        persistence().persistPending(TASK, pendingState('run-1'))
        def first = tip()

        when:
        persistence().persistPending(TASK, pendingState('run-1', 'run-2'))

        then:
        def second = tip()
        registered() == second
        parentsOf(second) == first
        changedIn(second) == [STATE]
        def state = gitOutput(cloneDir, 'show', second + ':' + STATE)
        state.contains('run-1') && state.contains('run-2')
    }

    // Scenario: git/git-task-persistence / A key rotation stacks a pending commit
    def "FR10: a rotated key's pending commit stacks on the previous one in the box too, admitted with its register"() {
        given:
        persistence().persistPending(TASK, pendingState('run-1'))
        def previous = tip()

        when:
        persistence().persistPending(TASK, pendingState('run-2'))

        then:
        def rotated = tip()
        registered() == rotated
        parentsOf(rotated) == previous
        changedIn(rotated) == [STATE]
        def state = gitOutput(cloneDir, 'show', rotated + ':' + STATE)
        state.contains('run-2') && !state.contains('run-1')
        rounds.closed().registered() == rotated
        gitOutput(box.workingCopy, 'rev-parse', 'HEAD') == rotated
    }

    // Scenario: git/git-task-persistence / A pending commit is built from the registered commit alone
    def "FR20: a path a check staged in the box's own index does not enter the pending commit"() {
        given:
        new File(box.workingCopy.toFile(), 'check-report.txt').text = 'staged by the check'
        runner.run(box.workingCopy, 'add', 'check-report.txt')

        when:
        persistence().persistPending(TASK, pendingState('run-1'))

        then:
        def pending = tip()
        parentsOf(pending) == snapshot
        changedIn(pending) == [STATE]
        runner.run(cloneDir, 'cat-file', '-e', pending + ':check-report.txt').exitCode() != 0
    }

    // Scenario: git/git-task-persistence / A harvested pending commit that changes another path is refused
    def "FR20: a harvested pending commit that #tampering is a boundary violation and the register does not move"() {
        given: 'a box that rewrites its HEAD between the in-box commit and the harvest'
        Closure<?> act = tamper
        def tamperingBox = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('tbox'))) {
                    @Override
                    void harvest() {
                        act.call(workingCopy)
                        super.harvest()
                    }
                }
        tamperingBox.materialize(BRANCH, null)

        when:
        persistence(tamperingBox).persistPending(TASK, pendingState('run-1'))

        then:
        def refused = thrown(RoundBoundaryViolationException)
        refused.message.contains(evidence)
        registered() == snapshot
        tip() == snapshot
        rounds.closed().registered() == snapshot

        where:
        tampering << [
            'changes another path',
            'sits on another parent'
        ]
        evidence << [
            'changes a path besides .gnomish-task/state.json',
            "is not the round's last registered commit"
        ]
        tamper << [
            { Path wc ->
                // The factory's state.json kept, one more path carried: only the tree check sees it.
                new GitProcessRunner().run(wc, 'checkout', 'HEAD', '--', STATE)
                new File(wc.toFile(), 'smuggled.txt').text = 'x'
                new GitProcessRunner().run(wc, 'add', 'smuggled.txt')
                new GitProcessRunner().run(wc, 'commit', '-q', '--amend', '--no-edit')
            },
            { Path wc ->
                new GitProcessRunner().run(wc, 'commit', '-q', '--allow-empty', '-m', 'daemon')
            }
        ]
    }

    // D21, "A round with no registered snapshot writes no pending commit"
    def "FR20: a cell with no snapshot refuses the pending write before anything is written in the box"() {
        given:
        rounds.open(rounds.opened())
        def boxHead = gitOutput(box.workingCopy, 'rev-parse', 'HEAD')

        when:
        persistence().persistPending(TASK, pendingState('run-1'))

        then:
        thrown(GitPersistFailedException)
        gitOutput(box.workingCopy, 'rev-parse', 'HEAD') == boxHead
        registered() == snapshot
    }
}
