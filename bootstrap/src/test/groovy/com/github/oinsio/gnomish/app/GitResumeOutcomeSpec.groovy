package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState

import java.nio.file.Files
/**
 * FR5, FR8, FR10, UX2 of add-git-workflow (task 4.7): {@code run()}'s outcome-driven
 * continuation — {@code null} continues the engine loop directly from {@code state.json}'s
 * recorded position (salvaging or discarding interrupted leftovers per {@code --discard-work});
 * {@code paused} continues without a prompt (UX2; FR5 of make-run-headless); {@code completed}
 * reports and exits without touching the worktree or branch again. The {@code escalated} arm —
 * the {@code --decision} resolution — is {@link GitResumeDecisionSpec}. Standard input is empty
 * throughout: a headless resume reads nothing.
 */
class GitResumeOutcomeSpec extends GitResumeSpecBase {

    // FR8: outcome null (process died mid-visit) continues the engine loop straight from
    // state.json's recorded position, no dialog, and eventually records Completed.
    def "run() with outcome null continues from the recorded position and records Completed"() {
        given: 'a task with one persisted round but no recorded outcome — the process died mid-visit'
        def taskId = 'PROJ-10'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        persistOneRound(taskId, TaskState.atStageStart('build'))

        when: 'resuming drives one more fake-agent round to completion'
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, null)

        then: 'the branch records a Completed outcome and the worktree is removed'
        gitExitCode(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}") == 0
        !Files.exists(expectedWorktree(taskId))
    }

    // FR10, D10: an interrupted round's uncommitted leftovers are salvaged by default — committed
    // as-is with the fixed "gnomish: salvage" message, distinct from the round commit, before the
    // engine loop continues and drives the task to completion.
    def "run() without --discard-work salvages an interrupted round's uncommitted leftovers as a service commit"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-30'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        Files.writeString(worktree.resolve('half-done.txt'), 'interrupted work')

        when: 'resuming with the default (no --discard-work) drives the task to completion'
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, null)

        then: 'the branch history contains a distinct salvage commit ahead of the round commit'
        def subjects = gitOutput(cloneDir, 'log', "gnomish/${taskId}", '--format=%s')
        subjects.contains('gnomish: salvage')

        and: 'the task still reached completion afterward'
        gitExitCode(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}") == 0
    }

    // FR10, D10: --discard-work resets the worktree to the last recorded round instead of
    // salvaging, so no salvage commit appears and the interrupted round is replayed cleanly.
    def "run() with --discard-work discards an interrupted round's uncommitted leftovers, no salvage commit"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-31'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        Files.writeString(worktree.resolve('half-done.txt'), 'interrupted work')

        when: 'resuming with --discard-work drives the task to completion'
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(new RunOrder(cloneDir, null, pipeline(), true), taskId, null)

        then: 'no salvage commit landed on the branch — the leftovers were discarded, not committed'
        def subjects = gitOutput(cloneDir, 'log', "gnomish/${taskId}", '--format=%s')
        !subjects.contains('gnomish: salvage')

        and: 'the task still reached completion afterward'
        gitExitCode(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}") == 0
    }

    // FR10, D10: --discard-work actually calls WorktreeSalvage#discard — proven directly against a
    // worktree left dirty on disk (no completion drive involved), distinguishing "discard() ran and
    // reset the tree" from "discard() was never called" (PIT: VoidMethodCallMutator survivor).
    def "run() with --discard-work removes uncommitted leftovers from the worktree before continuing"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-32'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        Files.writeString(worktree.resolve('half-done.txt'), 'interrupted work')
        assert Files.exists(worktree.resolve('half-done.txt'))

        when: 'resuming with --discard-work drives the task to completion'
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(new RunOrder(cloneDir, null, pipeline(), true), taskId, null)

        then: 'the leftover file itself was discarded from the worktree, not just left uncommitted'
        !Files.exists(worktree.resolve('half-done.txt'))
    }

    // FR8, UX2; FR5 of make-run-headless: outcome paused continues with no checkpoint line and no
    // prompt — the resume is the approval (FR4 of make-checkpoint-gate-durable): one approval commit
    // opens the gate, then the engine continues from the approved state.
    def "run() with outcome paused at a gate approves it in one commit and continues to completion without a checkpoint line"() {
        given: 'a task paused after "build" passed — its recorded position the gate of "build"'
        def taskId = 'PROJ-12'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def gateState = new TaskState(new Position.AwaitingApproval('build'), 0, [], ExecutorUsage.none())
        persistOneRound(taskId, gateState)
        repository().recordOutcome(taskId, new TaskOutcome.Paused(gateState, 'build'), TrackerWrite.OWED)

        def out = new ByteArrayOutputStream()

        when: 'stdin is empty'
        newResumeRunner(new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, 'UTF-8'))
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, null)

        then: 'nothing about the checkpoint was printed and nothing was asked'
        def printed = out.toString('UTF-8')
        !printed.contains('Manual checkpoint reached')
        !printed.contains('Press Enter to continue')

        and: 'the branch records a fresh Completed outcome (the approved state is PipelineEnd)'
        gitExitCode(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}") == 0
        !Files.exists(expectedWorktree(taskId))

        and: 'exactly one approval commit opened the gate, right after the park'
        def subjects = gitOutput(cloneDir, 'log', '--reverse', '--format=%s', "gnomish/${taskId}").readLines()
        subjects.count('gnomish: task approved') == 1
        subjects[subjects.indexOf('gnomish: task approved') - 1] == 'gnomish: task paused'
    }

    // FR8: outcome completed reports and exits without touching the worktree or branch again — no
    // engine run, no new commit past task.json's recorded Completed outcome.
    //
    // GitTaskRepository#recordOutcome's Completed path also runs the FR15 cleanup commit, which
    // removes .gnomish-task/ (including task.json itself) from the branch tip and the worktree —
    // by design, since a genuinely completed task's audit trail lives in branch history, not the
    // tip. That makes --resume on an already-cleaned-up Completed task a non-scenario (bootstrap
    // has no task.json left to read). This spec instead models a Completed task.json that has not
    // yet gone through cleanup — a valid intermediate state the outcome switch must still handle
    // correctly — by writing task.json directly rather than through the full recordOutcome path.
    def "run() with outcome completed reports and exits without further touching the worktree or branch"() {
        given: 'a task whose task.json already records Completed, before FR15 cleanup ran'
        def taskId = 'PROJ-13'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def finalState = TaskState.atStageStart('build')
        persistOneRound(taskId, finalState)
        writeCompletedTaskJson(taskId)
        def tipBefore = gitOutput(cloneDir, 'rev-parse', "gnomish/${taskId}")

        and: 'reportCompleted prints via System.out directly, mirroring the GitModeRunner banner'
        def originalOut = System.out
        def out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, null)

        then: 'a status report was printed, naming the task'
        out.toString('UTF-8').contains(taskId)

        and: 'no new commit landed on the branch beyond bootstrap materializing a worktree'
        gitOutput(cloneDir, 'rev-parse', "gnomish/${taskId}") == tipBefore

        cleanup:
        System.out = originalOut
    }

    // FR6, FR8, task 4.7: runToTerminalBoundary's Aborted branch — a broken durability guarantee
    // (round-boundary violation, design D12) surfacing during a resumed run is recorded through
    // GitOutcomeRecorder exactly like GitModeRunner's own fresh-run path, and the worktree is kept
    // unconditionally for forensics rather than removed.
    def "run() with outcome null records an Aborted outcome and keeps the worktree when the round-boundary protocol is violated"() {
        given: 'a task with one persisted round, resumed onto a worktree checked out to the wrong branch'
        def taskId = 'PROJ-33'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        gitExitCode(cloneDir, 'worktree', 'remove', '--force', worktree.toString())

        // The worktree at the deterministic path must still carry the task's own real
        // .gnomish-task/ files (bootstrap reads task.json/state.json straight off disk there) —
        // so the decoy branch is created off the real task branch's tip, just under a different
        // name, rather than off HEAD (which would leave no .gnomish-task/ at all).
        gitExitCode(cloneDir, 'worktree', 'add', '-b', 'not-the-task-branch', worktree.toString(),
                "gnomish/${taskId}")

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, null)

        then:
        def ex = thrown(AbortedException)
        ex.outcome() != null

        and: 'the aborted outcome is durably recorded in the worktree HEAD via GitOutcomeRecorder'
        def taskJson = gitOutput(worktree, 'show', 'HEAD:.gnomish-task/task.json')
        taskJson.contains('"aborted"')

        and: 'the worktree is kept, unconditionally, for forensics'
        Files.isDirectory(worktree)
    }
}
