package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files

/**
 * FR9, D3 of add-tracker-port (task 5.6): {@link TakeResumeRunner#resumeWithoutDecision} —
 * salvage-or-discard the interrupted round's leftovers, then run the engine exactly once with no
 * console dialog, mapping the terminal outcome via {@code TakeOutcomeMapper}.
 *
 * <p>FR4, FR7, FR11 of make-checkpoint-gate-durable (M3): a continuation over a recorded outcome
 * lands its one lifecycle commit — the resumed write, or the approval of a gate — on the real
 * branch before the first round, and a gate whose park was lost is parked without a round.
 */
class TakeResumeRunnerWithoutDecisionSpec extends TakeResumeSpecBase {

    // FR9: a Completed engine run maps to Delivered and the worktree is cleaned up (removed),
    // exactly as GitOutcomeRecorder does for a fresh manual run.
    def "resumeWithoutDecision runs the engine once and maps a Completed outcome to Delivered, worktree removed"() {
        given: 'a task with one persisted round — resuming drives it straight to the pipeline end'
        def taskId = 'PROJ-1'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, resumableBaseRef()), TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(
                resumeOrder(pipeline(), taskId), bootstrap, state)

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
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, resumableBaseRef()), TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        Files.writeString(bootstrap.worktreePath().resolve('half-done.txt'), 'interrupted work')

        when:
        runner.resumeWithoutDecision(
                resumeOrder(pipeline(), taskId), bootstrap, state)

        then: 'a distinct salvage commit landed ahead of the round commit'
        def subjects = gitOutput(cloneDir, 'log', "gnomish/${taskId}", '--format=%s')
        subjects.contains('gnomish: salvage')
    }

    // FR9: --discard-work resets the worktree to the last recorded round instead of salvaging —
    // no salvage commit, and the leftover file itself is gone before the engine resumes.
    def "resumeWithoutDecision with discardWork discards interrupted leftovers, no salvage commit"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-3'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, resumableBaseRef()), TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        Files.writeString(bootstrap.worktreePath().resolve('half-done.txt'), 'interrupted work')

        when:
        runner.resumeWithoutDecision(
                resumeOrder(pipeline(), taskId, true), bootstrap, state)

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
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, resumableBaseRef()), TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        Files.writeString(bootstrap.worktreePath().resolve('half-done.txt'), 'interrupted work')

        when: 'the pipeline pauses at a manual checkpoint, keeping the worktree afterward'
        runner.resumeWithoutDecision(
                resumeOrder(pipeline(AdvancementModeManual()), taskId, true), bootstrap, state)

        then: 'the worktree survives (Paused keeps it) and the leftover file was wiped by discard'
        Files.exists(bootstrap.worktreePath())
        !Files.exists(bootstrap.worktreePath().resolve('half-done.txt'))
    }

    // FR9, D3: a Paused (manual checkpoint) engine outcome maps to AwaitingHuman(CHECKPOINT) and
    // the worktree is kept, not removed — a park needs the worktree for the next resume.
    def "resumeWithoutDecision maps a Paused outcome to AwaitingHuman(CHECKPOINT), worktree kept"() {
        given: 'a manual-checkpoint pipeline, positioned so the single stage passes and pauses'
        def taskId = 'PROJ-4'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, resumableBaseRef()), TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(
                resumeOrder(pipeline(AdvancementModeManual()), taskId), bootstrap, state)

        then:
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.CHECKPOINT
        Files.isDirectory(bootstrap.worktreePath())
    }


    // FR7 of make-checkpoint-gate-durable (M3): an INFRA-kind park returned without a reply is
    // consumed by ONE resumed commit — outcome null, attempts reset — before the first round runs.
    def "an infrastructure return lands the resumed commit, outcome cleared and attempts reset, before any round"() {
        given: 'a CannotVerify park recorded on a real branch, its marker cleared by the landed park'
        def taskId = 'PROJ-5'
        createTask(taskId)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def escalatedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        def report = new EscalationReport.CannotVerify(
                new CheckRef(0, UntrustedText.manifest('tests')), UntrustedText.subprocess('down'), UntrustedText.subprocess(''))
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(escalatedState, report), TrackerWrite.OWED)
        repository().confirmTerminalWrite(taskId)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = newDecisionResume(runner, pipeline()).resumeReturned(
                resumeOrder(pipeline(), taskId), bootstrap, escalatedState)

        then:
        result instanceof TakeResult.Delivered

        and: 'the resumed commit sits between the park and the first round of the continued run'
        def subjects = subjectsOldestFirst(taskId)
        subjects.indexOf('gnomish: task resumed') == subjects.indexOf('gnomish: task escalated') + 2
        subjects.count('gnomish: task resumed') == 1
        subjects[subjects.indexOf('gnomish: task resumed') + 1].startsWith('gnomish: round build#')

        and: 'that one commit carries both halves: the outcome consumed and the attempts reset'
        def resumed = commitWithSubject(taskId, 'gnomish: task resumed')
        envelope(resumed, 'task.json').path('outcome').isNull()
        envelope(resumed, 'state.json').path('attemptsUsed').asInt() == 0
    }

    // FR4 of make-checkpoint-gate-durable (M3): a returned checkpoint is opened by ONE approval
    // commit before the run continues; the manual last stage's approval writes the pipeline end.
    def "a returned gate lands the approval commit before the run continues"() {
        given: 'a manual pass at the gate, its park recorded and delivered'
        def taskId = 'PROJ-6'
        def gated = gateRound(taskId)
        repository().recordOutcome(taskId, new TaskOutcome.Paused(gated, 'build'), TrackerWrite.OWED)
        repository().confirmTerminalWrite(taskId)

        when:
        def result = loadedRoutes().route(resumeOrder(pipeline(AdvancementMode.MANUAL), taskId))

        then:
        result instanceof TakeResult.Delivered

        and: 'one approval commit, past the gate with the outcome cleared, before the completion'
        def subjects = subjectsOldestFirst(taskId)
        subjects.count('gnomish: task approved') == 1
        subjects.indexOf('gnomish: task approved') <subjects.indexOf('gnomish: task completed')
        def approved = commitWithSubject(taskId, 'gnomish: task approved')
        envelope(approved, 'task.json').path('outcome').isNull()
        envelope(approved, 'state.json').path('position').path('type').asText() == 'pipelineEnd'
    }

    // FR11 of make-checkpoint-gate-durable: a gate killed before its park was recorded is parked —
    // the paused outcome commit lands, the checkpoint park is posted — and no round runs.
    def "a gate whose park was lost records and delivers the park, running no round"() {
        given: 'a manual pass at the gate, the process killed before the park commit'
        def taskId = 'PROJ-7'
        gateRound(taskId)
        def roundsBefore = subjectsOldestFirst(taskId).count {
            it.startsWith('gnomish: round')
        }

        when:
        def result = loadedRoutes().route(resumeOrder(pipeline(AdvancementMode.MANUAL), taskId))

        then:
        1 * tracker.park(REF, ParkReason.CHECKPOINT, _)
        result instanceof TakeResult.AwaitingHuman

        and: 'the owed park is on the branch and no round was added'
        def subjects = subjectsOldestFirst(taskId)
        subjects.last() == 'gnomish: task write-confirmed'
        subjects.count('gnomish: task paused') == 1
        subjects.count { it.startsWith('gnomish: round') } == roundsBefore
        !subjects.contains('gnomish: task approved')
    }

    private void createTask(String taskId) {
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, resumableBaseRef()),
                TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
    }

    /** A real round commit leaving the task at the gate of {@code build}, as a manual pass records it. */
    private TaskState gateRound(String taskId) {
        createTask(taskId)
        def passed = TaskState.atStageStart('build')
        def gated = new TaskState(new Position.AwaitingApproval('build'), 1, passed.attempts(), passed.totals())
        persistOneRound(taskId, gated)
        gated
    }

    private TakeLoadedBranchRoutes<ResumeBootstrap> loadedRoutes() {
        def runner = newTakeResumeRunner()
        def mechanics = new HostResumeMechanics(runner, taskGit, registeredClone, pipeline(AdvancementMode.MANUAL))
        new TakeLoadedBranchRoutes<>(mechanics, new TakeDecisionResume<>(mechanics), taskGit)
    }

    private List<String> subjectsOldestFirst(String taskId) {
        gitOutput(cloneDir, 'log', '--reverse', '--format=%s', "gnomish/${taskId}").readLines()
    }

    private String commitWithSubject(String taskId, String subject) {
        gitOutput(cloneDir, 'log', '--format=%H %s', "gnomish/${taskId}").readLines()
                .find { it.endsWith(' ' + subject) }
                .split(' ')[0]
    }

    private JsonNode envelope(String commit, String file) {
        new ObjectMapper().readTree(gitOutput(cloneDir, 'show', "${commit}:.gnomish-task/${file}"))
    }

    private static AdvancementMode AdvancementModeManual() {
        AdvancementMode.MANUAL
    }
}
