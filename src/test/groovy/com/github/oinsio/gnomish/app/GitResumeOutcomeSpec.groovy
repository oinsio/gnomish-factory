package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files

/**
 * FR5, FR8, FR10, UX2 of add-git-workflow (task 4.7): {@code run()}'s outcome-driven
 * continuation — {@code null} continues the engine loop directly from {@code state.json}'s
 * recorded position (salvaging or discarding interrupted leftovers per {@code --discard-work});
 * {@code escalated}/{@code paused} run the same dialogs the in-process path uses (UX2) before
 * continuing; {@code completed} reports and exits without touching the worktree or branch again.
 */
class GitResumeOutcomeSpec extends GitResumeSpecBase {

    // FR8: outcome null (process died mid-visit) continues the engine loop straight from
    // state.json's recorded position, no dialog, and eventually records Completed.
    def "run() with outcome null continues from the recorded position and records Completed"() {
        given: 'a task with one persisted round but no recorded outcome — the process died mid-visit'
        def taskId = 'PROJ-10'
        repository().createTask(context(taskId), null)
        persistOneRound(taskId, TaskState.atStageStart('build'))

        when: 'resuming drives one more round to completion (a bare Enter via the interactive executor)'
        newResumeRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')), System.out)
                .run(cloneDir, taskId, pipeline(), RunArguments.InteractiveMode.ALL, false)

        then: 'the branch records a Completed outcome and the worktree is removed'
        gitRunner.run(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}").exitCode() == 0
        !Files.exists(expectedWorktree(taskId))
    }

    // FR10, D10: an interrupted round's uncommitted leftovers are salvaged by default — committed
    // as-is with the fixed "gnomish: salvage" message, distinct from the round commit, before the
    // engine loop continues and drives the task to completion.
    def "run() without --discard-work salvages an interrupted round's uncommitted leftovers as a service commit"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-30'
        repository().createTask(context(taskId), null)
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        Files.writeString(worktree.resolve('half-done.txt'), 'interrupted work')

        when: 'resuming with the default (no --discard-work) drives the task to completion'
        newResumeRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')), System.out)
                .run(cloneDir, taskId, pipeline(), RunArguments.InteractiveMode.ALL, false)

        then: 'the branch history contains a distinct salvage commit ahead of the round commit'
        def subjects = gitRunner.run(cloneDir, 'log', "gnomish/${taskId}", '--format=%s').stdout()
        subjects.contains('gnomish: salvage')

        and: 'the task still reached completion afterward'
        gitRunner.run(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}").exitCode() == 0
    }

    // FR10, D10: --discard-work resets the worktree to the last recorded round instead of
    // salvaging, so no salvage commit appears and the interrupted round is replayed cleanly.
    def "run() with --discard-work discards an interrupted round's uncommitted leftovers, no salvage commit"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-31'
        repository().createTask(context(taskId), null)
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        Files.writeString(worktree.resolve('half-done.txt'), 'interrupted work')

        when: 'resuming with --discard-work drives the task to completion'
        newResumeRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')), System.out)
                .run(cloneDir, taskId, pipeline(), RunArguments.InteractiveMode.ALL, true)

        then: 'no salvage commit landed on the branch — the leftovers were discarded, not committed'
        def subjects = gitRunner.run(cloneDir, 'log', "gnomish/${taskId}", '--format=%s').stdout()
        !subjects.contains('gnomish: salvage')

        and: 'the task still reached completion afterward'
        gitRunner.run(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}").exitCode() == 0
    }

    // FR10, D10: --discard-work actually calls WorktreeSalvage#discard — proven directly against a
    // worktree left dirty on disk (no completion drive involved), distinguishing "discard() ran and
    // reset the tree" from "discard() was never called" (PIT: VoidMethodCallMutator survivor).
    def "run() with --discard-work removes uncommitted leftovers from the worktree before continuing"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-32'
        repository().createTask(context(taskId), null)
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        Files.writeString(worktree.resolve('half-done.txt'), 'interrupted work')
        assert Files.exists(worktree.resolve('half-done.txt'))

        when: 'resuming with --discard-work drives the task to completion'
        newResumeRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')), System.out)
                .run(cloneDir, taskId, pipeline(), RunArguments.InteractiveMode.ALL, true)

        then: 'the leftover file itself was discarded from the worktree, not just left uncommitted'
        !Files.exists(worktree.resolve('half-done.txt'))
    }

    // FR5, FR8, UX2: outcome escalated drives the same decision dialog the in-process path uses,
    // appends the decision (resetting outcome to null in the same commit), and continues.
    def "run() with outcome escalated drives the decision dialog then continues to completion"() {
        given: 'a task escalated after one persisted round'
        def taskId = 'PROJ-11'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(afterRound, report))

        and: 'stdin supplies the decision answer, then a bare Enter for the resumed round'
        def script = 'go ahead' + System.lineSeparator() + System.lineSeparator()
        def out = new ByteArrayOutputStream()

        when:
        newResumeRunner(new ByteArrayInputStream(script.getBytes('UTF-8')), new PrintStream(out, true, 'UTF-8'))
                .run(cloneDir, taskId, pipeline(), RunArguments.InteractiveMode.ALL, false)

        then: 'the rendered escalation and the resume prompt were printed — the same dialog as in-process'
        def printed = out.toString('UTF-8')
        printed.contains('continue?')
        printed.contains('Decision (empty to resume without one)')

        and: 'the branch records the appended decision and a new terminal outcome'
        gitRunner.run(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}").exitCode() == 0
        !Files.exists(expectedWorktree(taskId))

        and: 'the answered decision text was actually committed via GitTaskRepository#appendDecision — proven'
        // by finding it in some historical task.json blob on the branch, since the FR15 cleanup
        // commit at Completed removes the tip's .gnomish-task/ entirely.
        def historicalTaskJsons = gitRunner.run(cloneDir, 'log', "gnomish/${taskId}", '--format=%H').stdout()
                .lines().collect { gitRunner.run(cloneDir, 'show', "${it}:.gnomish-task/task.json") }
                .findAll { it.exitCode() == 0 }
                .collect { it.stdout() }
        historicalTaskJsons.any { it.contains('go ahead') }
    }

    // FR8, UX2: outcome paused confirms with the same checkpoint dialog the in-process path uses,
    // then continues to the next stage (here: the pipeline end, since there is only one stage).
    def "run() with outcome paused confirms then continues to completion"() {
        given: 'a task paused after "build" passed — its recorded position already advanced to PipelineEnd'
        def taskId = 'PROJ-12'
        repository().createTask(context(taskId), null)
        def endState = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())
        persistOneRound(taskId, endState)
        repository().recordOutcome(taskId, new TaskOutcome.Paused(endState, 'build'))

        def out = new ByteArrayOutputStream()

        when: 'a bare Enter confirms the checkpoint'
        newResumeRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')),
                new PrintStream(out, true, 'UTF-8'))
                .run(cloneDir, taskId, pipeline(), RunArguments.InteractiveMode.ALL, false)

        then: 'the checkpoint confirmation was printed — the same dialog as in-process'
        def printed = out.toString('UTF-8')
        printed.contains("Stage 'build' passed")
        printed.contains('Press Enter to continue')

        and: 'the branch records a fresh Completed outcome (the engine reaches PipelineEnd immediately)'
        gitRunner.run(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}").exitCode() == 0
        !Files.exists(expectedWorktree(taskId))
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
        repository().createTask(context(taskId), null)
        def finalState = TaskState.atStageStart('build')
        persistOneRound(taskId, finalState)
        writeCompletedTaskJson(taskId)
        def tipBefore = gitRunner.run(cloneDir, 'rev-parse', "gnomish/${taskId}").stdout().trim()

        and: 'reportCompleted prints via System.out directly, mirroring the GitModeRunner banner'
        def originalOut = System.out
        def out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(cloneDir, taskId, pipeline(), RunArguments.InteractiveMode.ALL, false)

        then: 'a status report was printed, naming the task'
        out.toString('UTF-8').contains(taskId)

        and: 'no new commit landed on the branch beyond bootstrap materializing a worktree'
        gitRunner.run(cloneDir, 'rev-parse', "gnomish/${taskId}").stdout().trim() == tipBefore

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
        repository().createTask(context(taskId), null)
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        gitRunner.run(cloneDir, 'worktree', 'remove', '--force', worktree.toString())

        // The worktree at the deterministic path must still carry the task's own real
        // .gnomish-task/ files (bootstrap reads task.json/state.json straight off disk there) —
        // so the decoy branch is created off the real task branch's tip, just under a different
        // name, rather than off HEAD (which would leave no .gnomish-task/ at all).
        gitRunner.run(cloneDir, 'worktree', 'add', '-b', 'not-the-task-branch', worktree.toString(),
                "gnomish/${taskId}")

        when:
        newResumeRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')), System.out)
                .run(cloneDir, taskId, pipeline(), RunArguments.InteractiveMode.ALL, false)

        then:
        def ex = thrown(AbortedException)
        ex.outcome() != null

        and: 'the aborted outcome is durably recorded in the worktree HEAD via GitOutcomeRecorder'
        def taskJson = gitRunner.run(worktree, 'show', 'HEAD:.gnomish-task/task.json').stdout()
        taskJson.contains('"aborted"')

        and: 'the worktree is kept, unconditionally, for forensics'
        Files.isDirectory(worktree)
    }
}
