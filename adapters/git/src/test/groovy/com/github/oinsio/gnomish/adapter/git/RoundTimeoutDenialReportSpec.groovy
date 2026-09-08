package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchStateResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * M1 of fix-denial-attribution-durability, the chain closed end to end: a round killed on its
 * round timeout after the guard blocked an egress attempt puts that denial in front of the
 * reviewer in BOTH documents — {@code task.json} on the task branch and the {@code status.json}
 * a reader renders from it — while {@code attemptsUsed} and the attempt history stay exactly as
 * a round that never closed leaves them.
 *
 * <p>This is the scenario the change exists for (proposal U1): before it, the denials of a
 * round that hung mid-exfiltration were drained and written to the factory's own log only, so
 * the one gnome a reviewer most needs to see reported nothing but the hang.
 *
 * <p>The kill is modelled as the executor throwing an {@link ExecutorFailure} wrapping a
 * round-timeout failure with the drained denials — which is exactly what the agent adapter
 * does on that path; that the real {@code RoundTimeoutException} is wrapped this way is pinned
 * by {@code FailedRoundDenialSpec} in {@code :adapters:agent}, a module this one deliberately
 * takes no edge to. What is asserted here is the rest of the chain: engine escalation → task
 * branch → both rendered documents.
 *
 * <p>Implements FR1, FR2, NFR-O1, UX1, M1 of fix-denial-attribution-durability.
 */
class RoundTimeoutDenialReportSpec extends Specification implements BareGitRepoFixture {

    private static final String TASK_ID = 'manual-20260716-143502-t1'

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

    def "M1: a round killed on its round timeout shows its egress denial under the escalation in both documents"() {
        given: 'a round the guard blocked, then the round timeout killed before it could close'
        def denial = Denial.unidentified(new Finding(
                        'egress denied: paste.example.com:443', 'paste.example.com:443/upload', 'kind=http method=POST'))
        def context = new TaskContext(TASK_ID, 'Fix flaky OrderServiceSpec', 'body', [])

        when: 'the engine runs that round and parks the task on the branch'
        def outcome = runRoundKilledBy(
                new ExecutorFailure(new RuntimeException('round timed out after PT15M'), [denial]), context)
        def repository = new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
        repository.createTask(context, 'HEAD', BaseRule.LOCAL_HEAD, TaskState.atStageStart('build'))
        repository.recordOutcome(TASK_ID, outcome)

        then: 'the escalation carries the denial, and the killed round burned no attempt (FR1)'
        def report = (outcome as TaskOutcome.Escalated).report() as EscalationReport.CannotExecute
        report.denials() == [denial]
        outcome.finalState().attemptsUsed() == 0
        outcome.finalState().attempts().isEmpty()

        and: 'task.json on the branch carries it under lastEscalation, with the denied host, path and method'
        def taskJson = committedTaskJson()
        taskJson.contains('"cannotExecute"')
        taskJson.contains('"denials"')
        taskJson.contains('egress denied: paste.example.com:443')
        taskJson.contains('paste.example.com:443/upload')
        taskJson.contains('kind=http method=POST')

        and: 'status.json rendered from that branch shows the same denial under the same escalation'
        def result = new BranchStateReader(runner).read(cloneDir, TASK_ID)
        def statusJson = mapper.serialize((result as BranchStateResult.Found).report())
        statusJson.contains('"type" : "cannotExecute"')
        statusJson.contains('"message" : "egress denied: paste.example.com:443"')
        statusJson.contains('"location" : "paste.example.com:443/upload"')

        and: 'neither document invented an attempt to hold it (M1)'
        statusJson.contains('"attemptsUsed" : 0')
        statusJson.contains('"attempts" : [ ]')
    }

    /** Runs one stage whose executor dies the way a round-timeout kill leaves it. */
    private static TaskOutcome runRoundKilledBy(RuntimeException thrown, TaskContext context) {
        def executor = new ScriptedExecutor()
        executor.toThrow = thrown
        def clock = new VirtualClock()
        def ports = new EnginePorts(
                executor, new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), new RecordingEventListener(),
                new InMemoryAttemptPersistence(), clock, new VirtualSleeper(clock))
        new Engine().run(
                pipeline(), context, TaskState.atStageStart('build'), new FakeWorkspace(), ports) as TaskOutcome
    }

    private String committedTaskJson() {
        worktreesRoot.resolve('clone').resolve(TASK_ID).resolve('.gnomish-task').resolve('task.json').toFile().text
    }

    private static PipelineDefinition pipeline() {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [:])
        ],
        new AutonomyLimits(5), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(5), [stage])
    }
}
