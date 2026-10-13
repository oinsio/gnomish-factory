package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.SeededCloneFixture
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, NFR-O1 of add-git-workflow; FR16 of harden-task-branch-contract: {@code gnomish status}
 * reads a task branch at its tip, so it answers for a task another process is still working on —
 * whatever that process has in its worktree and not yet committed is not the task's state — and
 * for a branch nothing has been recorded on yet. Real git throughout, as {@link StatusCommandSpec}.
 */
class StatusFromAnotherTerminalSpec extends Specification implements SeededCloneFixture, StdoutCaptureFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupSeededClone()
    }

    private String status(String... args) {
        captureStdout {
            new StatusCommand(TaskGitFixture.realClaimless(), RegisteredCloneFixture.scope(registeredClone), liveConsole())
            .run(new DefaultApplicationArguments((['status', '--dir=' + cloneDir] + args.toList()) as String[]))
        }
    }

    /** Everything a read could disturb: the clone's refs, checkout and files, and the task's worktree. */
    private Map snapshot(Path worktree) {
        [
            refs: gitOutput(cloneDir, 'for-each-ref'),
            head: gitOutput(cloneDir, 'rev-parse', 'HEAD'),
            porcelain: gitOutput(cloneDir, 'status', '--porcelain'),
            worktrees: gitOutput(cloneDir, 'worktree', 'list', '--porcelain'),
            inFlight: gitOutput(worktree, 'status', '--porcelain'),
        ]
    }

    // FR13: the round commit is the task's state; the working process's uncommitted work is not.
    // Scenario: cli/task-inspection / Status of a running task from another terminal
    def "FR13: status of a task another process is mid-round on prints the last round commit and mutates nothing"() {
        given: 'a task with one committed round, its worker now mid-round with uncommitted work'
        persistRound('LIVE-1', TaskState.atStageStart('implement').recordUnburnedRound(
                        round(0, AttemptRecord.Result.PASSED, 500, 100)))
        def worktree = worktreeFor('LIVE-1')
        Files.writeString(worktree.resolve('half-written.txt'), 'the next round, unfinished')
        Files.writeString(worktree.resolve('.gnomish-task/state.json'), '{ "being": "rewritten"')
        def before = snapshot(worktree)

        when:
        def output = status('LIVE-1')

        then: 'the state is the committed round, read at the tip'
        output.contains('Task: LIVE-1')
        output.contains('Round 0')

        and: 'nothing in the clone or the worker\'s worktree moved'
        snapshot(worktree) == before
    }

    // FR16: a branch with only its STARTED commit classifies as Created and renders with its
    //     snapshot title, no round and no outcome.
    // F103: scenario text aligned to what this test asserts
    // Scenario: cli/task-inspection / Freshly created branch renders as pending
    def "FR16: a branch holding only the STARTED commit renders as not yet started, with its title"() {
        given:
        TaskGitFixture.realClaimless().store().taskRepository(registeredClone).createTask(
                new TaskContext('FRESH-1', UntrustedText.tracker('Fix the fresh thing'), UntrustedText.tracker('Body'), []),
                TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        when:
        def text = status('FRESH-1')
        def json = status('FRESH-1', '--json')
        def listing = status()

        then: 'the snapshot title and the first stage, and no round run'
        text.contains('Task: FRESH-1 — Fix the fresh thing')
        text.contains('Stage: implement')
        !text.contains('Round ')

        and: 'no outcome is claimed, and the listing names the shape the classifier gave it'
        json.contains('"outcome" : null')
        json.contains('"attemptsUsed" : 0')
        listing.readLines().find { it.contains('FRESH-1') }.contains('Created')
    }
}
