package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.status.Activity
import com.github.oinsio.gnomish.status.LiveActivity
import com.github.oinsio.gnomish.status.StatusReport
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR4, M2 of add-git-workflow: the equivalence contract test. A {@link StatusReport} rendered
 * from live engine events must be equivalent — modulo the live-only fields that a state-file
 * read can never reconstruct ({@code activity}, {@code attemptLimit}, see {@link
 * BranchStateReader}'s class javadoc) — to a {@link StatusReport} rendered from the same task's
 * persisted {@code .gnomish-task/} state files. Both renderings are anchored against the same
 * task/attempt data that backs {@code status-report-v1.reference.json}
 * ({@code StatusReportJsonMapperSpec#referenceReport}), so the fixture stays the ground truth
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
    Path worktreesRoot

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    def "FR4: StatusReport rendered from state files is equivalent to the live-rendered report, anchored by status-report-v1.reference.json"() {
        given: 'the same task/attempt data that backs the reference fixture, rendered live'
        def taskId = 'manual-20260716-143502-x7'
        def context = new TaskContext(taskId, 'Fix flaky OrderServiceSpec', 'body',
                [
                    new Decision('patch in place', 'plan', 'operator', Instant.parse('2026-07-16T14:21:30Z'))
                ])
        def state = referenceTaskState()

        def escalation = new EscalationReport.DecisionNeeded(
                'Refactor the retry helper or patch in place?', ['refactor', 'patch'])
        def liveActivity = new LiveActivity(
                new Activity.Verifying(new CheckRef(0, 'command:./gradlew test'), Instant.parse('2026-07-16T14:41:02Z')),
                escalation, null)
        def liveReport = StatusReport.build(context, state, 3, liveActivity)

        and: 'the equivalent task.json + state.json content, committed to the task branch exactly as the git adapters would'
        def taskRepository = new GitTaskRepository(runner, cloneDir, worktreesRoot)
        taskRepository.createTask(new TaskContext(taskId, context.title(), context.body(), []), null)
        def worktree = worktreesRoot.resolve('clone').resolve(taskId)
        def trace = new ToolTrace(new AttemptKey(taskId, 'implement', 1), [
            new ToolCall(0, 'Edit', Instant.parse('2026-07-16T14:35:10Z'), Duration.ofMillis(2100))
        ])
        new GitAttemptPersistence(runner, worktree, taskId).persist(taskId, state, trace)

        and: 'the task escalated (recording lastEscalation durably, FR5) and was then resumed with the decision — outcome resets to null while lastEscalation is retained, exactly like the reference fixture (outcome: null, lastEscalation populated)'
        taskRepository.recordOutcome(taskId, new TaskOutcome.Escalated(state, escalation))
        taskRepository.appendDecision(taskId, context.decisions().first())

        when: 'both are rendered through the same JSON mapper'
        def fullLiveJson = mapper.serialize(liveReport)
        def liveJson = mapper.serialize(withoutLiveOnlyFields(liveReport))

        def result = new BranchStateReader(runner).read(cloneDir, taskId)
        def stateFileReport = (result as BranchStateResult.Found).report()
        def stateFileJson = mapper.serialize(stateFileReport)

        then: 'the fully-live rendering matches the shared reference fixture (the ground truth anchor)'
        fullLiveJson == referenceJsonText()

        and: 'the state-file rendering is equivalent to the live rendering, live-only fields excepted'
        stateFileJson == liveJson
    }

    /** Same attempt/state shape as {@code StatusReportJsonMapperSpec#referenceReport}. */
    private static TaskState referenceTaskState() {
        def passCheck = new CheckResult(
                new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(3))
        def failFinding = new Finding('command exited with 1', null, '…output tail…')
        def failCheck = new CheckResult(
                new CheckRef(1, 'command:./gradlew test'), new Verdict.Fail([failFinding]), Duration.ofMillis(41250))

        def attemptUsage = new ExecutorUsage(
                Duration.ofMillis(183000),
                [
                    new ToolUsage('Edit', 4, Duration.ofMillis(2100))
                ],
                ['claude-sonnet-5': new TokenUsage(1200, 5400, 30000, 410000)])

        def attempt = new AttemptRecord(
                1,
                AttemptRecord.Result.QUALITY_FAILURE,
                Instant.parse('2026-07-16T14:35:10Z'),
                [passCheck, failCheck],
                attemptUsage,
                JudgeUsage.none())

        def totalsUsage = new ExecutorUsage(
                Duration.ofMillis(232000),
                [
                    new ToolUsage('Edit', 4, Duration.ofMillis(2100))
                ],
                ['claude-sonnet-5': new TokenUsage(1450, 6100, 30000, 512000)])

        new TaskState(new Position.AtStage('implement'), 1, [attempt], totalsUsage)
    }

    /**
     * A state-file read never has a live process to observe: {@code activity} and {@code
     * attemptLimit} are always null on that side (see {@link BranchStateReader}'s class
     * javadoc). Equivalence is defined modulo those two fields, so the live side is stripped of
     * them before comparison — everything else (task identity, position, attempts, totals,
     * decisions, outcome, lastEscalation) must match exactly.
     */
    private static StatusReport withoutLiveOnlyFields(StatusReport report) {
        new StatusReport(
                report.taskId(), report.title(), report.body(), report.currentStage(),
                report.attemptsUsed(), null, report.attempts(), report.decisions(), report.lastDecision(),
                report.totals(), null, report.outcome(), report.lastEscalation())
    }

    private static String referenceJsonText() {
        StatusReportEquivalenceContractSpec.getResourceAsStream('/status-report-v1.reference.json').getText('UTF-8')
    }
}
