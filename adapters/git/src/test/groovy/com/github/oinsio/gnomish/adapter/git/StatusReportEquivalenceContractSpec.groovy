package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.BranchStateResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.status.Outcome
import com.github.oinsio.gnomish.status.StatusReport
import com.github.oinsio.gnomish.status.StatusReportReferenceFixture
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR4, M2 of add-git-workflow: the equivalence contract test. A {@link StatusReport} built in
 * memory from a task's context, state and recorded escalation/outcome must render byte-identically
 * to the {@link StatusReport} {@link BranchStateReader} builds from the same task's persisted
 * {@code .gnomish-task/} state files — every field is state-derived, so there is nothing to except
 * (FR6 of make-run-headless withdrew the live-only {@code activity} and {@code attemptLimit}). Both
 * renderings are anchored against the same
 * task/attempt data that backs {@code status-report-v1.reference.json} ({@link
 * StatusReportReferenceFixture}, the one owner of that sample since FR2 of
 * fix-denial-attribution-durability — this spec rebuilt it by hand until then), so it stays the ground truth
 * for the JSON contract shape on both sides (design D5): state-file DTOs are a separate
 * contract, kept aligned with the status-report view by this content-equivalence test rather
 * than by DTO reuse.
 */
class StatusReportEquivalenceContractSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def mapper = new StatusReportJsonMapper()

    Path cloneDir
    RegisteredClone registeredClone

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
    }

    def "FR4: StatusReport rendered from state files is identical to the in-memory report, anchored by status-report-v1.reference.json"() {
        given: 'the same task/attempt data that backs the reference fixture, built in memory'
        def taskId = StatusReportReferenceFixture.TASK_ID
        def context = StatusReportReferenceFixture.referenceContext()
        def state = StatusReportReferenceFixture.referenceTaskState()

        def escalation = StatusReportReferenceFixture.referenceEscalation()
        def memoryReport = StatusReportReferenceFixture.referenceReport()

        and: 'the equivalent task.json + state.json content, committed to the task branch exactly as the git adapters would'
        def taskRepository = new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock())
        taskRepository.createTask(new TaskContext(taskId, context.title(), context.body(), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def worktree = registeredClone.worktrees().resolve(taskId)

        and: 'the task escalated (recording lastEscalation durably, FR5) and was then resumed with the decision — outcome resets to null while lastEscalation is retained, exactly like the reference fixture (outcome: null, lastEscalation populated)'
        taskRepository.recordOutcome(taskId, new TaskOutcome.Escalated(state, escalation), TrackerWrite.OWED)
        // FR4 of harden-task-branch-contract: the decision's own commit carries the attempt-counter
        // reset, so the round the answered stage then runs is what puts the reference state back on
        // the branch — the same order a real resume writes these commits in.
        taskRepository.appendDecision(taskId, context.decisions().first(), state.resetAttempts())
        def trace = new ToolTrace(new AttemptKey(taskId, 'implement', 1), [
            new ToolCall(0, 'Edit', Instant.parse('2026-07-16T14:35:10Z'), Duration.ofMillis(2100))
        ])
        new GitAttemptPersistence(runner, worktree, taskId, ClaimEpochSource.NONE).persist(taskId, state, trace)

        when: 'both are rendered through the same JSON mapper'
        def memoryJson = mapper.serialize(memoryReport)

        def result = new BranchStateReader(runner, VirtualTimeGitRetries.gitInfrastructure()).read(cloneDir, taskId)
        def stateFileReport = (result as BranchStateResult.Found).report()
        def stateFileJson = mapper.serialize(stateFileReport)

        then: 'the in-memory rendering matches the shared reference fixture (the ground truth anchor)'
        memoryJson == referenceJsonText()

        and: 'the state-file rendering is byte-identical to it'
        stateFileJson == memoryJson
    }

    // FR4, M1 of fix-denial-report-attachment: the equivalence must hold with denials present too
    //     — a denial the round recorded has to survive the commit and read back the same on both
    //     sides, or a resuming instance would see a different history than the run did
    def "FR4: a passing attempt's denial survives the state file and renders identically on both sides"() {
        given: 'a passing round that recorded one egress denial'
        def taskId = 'manual-20260716-143502-d1'
        def denial = Denial.unidentified(new Finding(
                        'egress denied: paste.example.com:443', 'paste.example.com:443/upload', 'kind=http method=POST'))
        def check = new CheckResult(
                new CheckRef(0, UntrustedText.manifest('builtin:files_exist')), new Verdict.Pass(), Duration.ofMillis(3))
        def attempt = new AttemptRecord(
                0, AttemptRecord.Result.PASSED, Instant.parse('2026-07-16T14:35:10Z'),
                [check], ExecutorUsage.none(), JudgeUsage.none(), [denial])
        def state = new TaskState(new Position.AtStage('implement'), 1, [attempt], ExecutorUsage.none())
        def context = new TaskContext(taskId, UntrustedText.tracker('Fix flaky OrderServiceSpec'), UntrustedText.tracker('body'), [])
        def memoryReport = StatusReport.build(context, state, null, null)

        and: 'the round committed to the task branch exactly as the git adapters would'
        def taskRepository = new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock())
        taskRepository.createTask(context, TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def worktree = registeredClone.worktrees().resolve(taskId)
        new GitAttemptPersistence(runner, worktree, taskId, ClaimEpochSource.NONE)
                .persist(taskId, state, new ToolTrace(new AttemptKey(taskId, 'implement', 0), []))

        when: 'the branch is read back and both renderings go through the same mapper'
        def result = new BranchStateReader(runner, VirtualTimeGitRetries.gitInfrastructure()).read(cloneDir, taskId)
        def stateFileReport = (result as BranchStateResult.Found).report()

        then: 'the denial came back with the attempt, and the attempt is still passed (FR2)'
        stateFileReport.attempts()[0].denials() == [denial]
        stateFileReport.attempts()[0].result() == AttemptRecord.Result.PASSED

        and: 'the two renderings are byte-identical, denials included'
        mapper.serialize(stateFileReport) == mapper.serialize(memoryReport)
    }

    // FR2, M1 of fix-denial-attribution-durability: the round that could not execute left no
    //     attempt record, so its denials ride the escalation. The equivalence has to hold for
    //     them too — a resuming instance reading task.json must see the same blocked egress the
    //     run reported, and the attempt history must stay untouched by their presence.
    def "FR2: a cannotExecute escalation's denials survive task.json and render identically on both sides"() {
        given: 'a task parked after a round was killed on its round timeout, having tried a denied egress'
        def taskId = 'manual-20260716-143502-c1'
        def denial = Denial.unidentified(new Finding(
                        'egress denied: paste.example.com:443', 'paste.example.com:443/upload', 'kind=http method=POST'))
        def escalation = new EscalationReport.CannotExecute(UntrustedText.subprocess('round timed out after 15m'), [denial])
        def state = TaskState.atStageStart('implement')
        def context = new TaskContext(taskId, UntrustedText.tracker('Fix flaky OrderServiceSpec'), UntrustedText.tracker('body'), [])
        def memoryReport = StatusReport.build(context, state, escalation, new Outcome.Escalated(escalation))

        and: 'the park committed to the task branch exactly as the git adapters would'
        def taskRepository = new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock())
        taskRepository.createTask(context, TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), state)
        taskRepository.recordOutcome(taskId, new TaskOutcome.Escalated(state, escalation), TrackerWrite.OWED)

        when: 'the branch is read back and both renderings go through the same mapper'
        def result = new BranchStateReader(runner, VirtualTimeGitRetries.gitInfrastructure()).read(cloneDir, taskId)
        def stateFileReport = (result as BranchStateResult.Found).report()

        then: 'the escalation came back carrying its denial'
        (stateFileReport.lastEscalation() as EscalationReport.CannotExecute).denials() == [denial]

        and: 'the round that could not execute burned no attempt and recorded none (FR1)'
        stateFileReport.attemptsUsed() == 0
        stateFileReport.attempts().isEmpty()

        and: 'the two renderings are byte-identical, escalation denials included'
        mapper.serialize(stateFileReport) == mapper.serialize(memoryReport)
    }

    private static String referenceJsonText() {
        StatusReportEquivalenceContractSpec.getResourceAsStream('/status-report-v1.reference.json').getText('UTF-8')
    }
}
