package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import java.nio.file.Files

/**
 * FR9, D3 of add-tracker-port (task 5.6): {@link TakeResumeRunner#resumeWithoutDecision} —
 * salvage-or-discard the interrupted round's leftovers, then run the engine exactly once with no
 * console dialog, mapping the terminal outcome via {@code TakeOutcomeMapper}.
 */
class TakeResumeRunnerWithoutDecisionSpec extends TakeResumeSpecBase {

    // FR9: a Completed engine run maps to Delivered and the worktree is cleaned up (removed),
    // exactly as GitOutcomeRecorder does for a fresh manual run.
    def "resumeWithoutDecision runs the engine once and maps a Completed outcome to Delivered, worktree removed"() {
        given: 'a task with one persisted round — resuming drives it straight to the pipeline end'
        def taskId = 'PROJ-1'
        repository().createTask(context(taskId), null, TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(), state, RunArguments.InteractiveMode.ALL, false, tracker, REF, INSTANCE)

        then: 'the engine ran once (no manual dialog involved) and completed'
        result instanceof TakeResult.Delivered

        and: 'the branch records the Completed outcome and the worktree was removed'
        gitExitCode(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}") == 0
        !Files.exists(expectedWorktree(taskId))
    }

    // FR9: default (no --discard-work) salvages an interrupted round's leftovers as a distinct
    // service commit before the engine resumes, mirroring GitResumeContinuation.
    def "resumeWithoutDecision without discardWork salvages interrupted leftovers as a service commit"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-2'
        repository().createTask(context(taskId), null, TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        Files.writeString(bootstrap.worktreePath().resolve('half-done.txt'), 'interrupted work')

        when:
        runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(), state, RunArguments.InteractiveMode.ALL, false, tracker, REF, INSTANCE)

        then: 'a distinct salvage commit landed ahead of the round commit'
        def subjects = gitOutput(cloneDir, 'log', "gnomish/${taskId}", '--format=%s')
        subjects.contains('gnomish: salvage')
    }

    // FR9: --discard-work resets the worktree to the last recorded round instead of salvaging —
    // no salvage commit, and the leftover file itself is gone before the engine resumes.
    def "resumeWithoutDecision with discardWork discards interrupted leftovers, no salvage commit"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-3'
        repository().createTask(context(taskId), null, TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        Files.writeString(bootstrap.worktreePath().resolve('half-done.txt'), 'interrupted work')

        when:
        runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(), state, RunArguments.InteractiveMode.ALL, true, tracker, REF, INSTANCE)

        then: 'no salvage commit landed on the branch'
        def subjects = gitOutput(cloneDir, 'log', "gnomish/${taskId}", '--format=%s')
        !subjects.contains('gnomish: salvage')
    }

    // FR9: the leftover file itself is gone before the engine resumes — proven with a manual-
    // checkpoint pipeline so the worktree survives past the run (a Completed outcome would remove
    // the whole worktree via GitOutcomeRecorder regardless of whether discard() ran, which would
    // make the file's absence unobservable as evidence of discard specifically).
    def "resumeWithoutDecision with discardWork leaves the worktree wiped of leftovers, observable after a kept worktree"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-3b'
        repository().createTask(context(taskId), null, TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        Files.writeString(bootstrap.worktreePath().resolve('half-done.txt'), 'interrupted work')

        when: 'the pipeline pauses at a manual checkpoint, keeping the worktree afterward'
        runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(AdvancementModeManual()), state,
                RunArguments.InteractiveMode.ALL, true, tracker, REF, INSTANCE)

        then: 'the worktree survives (Paused keeps it) and the leftover file was wiped by discard'
        Files.exists(bootstrap.worktreePath())
        !Files.exists(bootstrap.worktreePath().resolve('half-done.txt'))
    }

    // FR9, D3: a Paused (manual checkpoint) engine outcome maps to AwaitingHuman(CHECKPOINT) and
    // the worktree is kept, not removed — a park needs the worktree for the next resume.
    def "resumeWithoutDecision maps a Paused outcome to AwaitingHuman(CHECKPOINT), worktree kept"() {
        given: 'a manual-checkpoint pipeline, positioned so the single stage passes and pauses'
        def taskId = 'PROJ-4'
        repository().createTask(context(taskId), null, TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(AdvancementModeManual()), state,
                RunArguments.InteractiveMode.ALL, false, tracker, REF, INSTANCE)

        then:
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.CHECKPOINT
        Files.isDirectory(bootstrap.worktreePath())
    }

    private static AdvancementMode AdvancementModeManual() {
        AdvancementMode.MANUAL
    }
}
