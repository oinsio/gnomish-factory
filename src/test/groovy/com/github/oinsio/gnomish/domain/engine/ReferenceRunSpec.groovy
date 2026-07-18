package com.github.oinsio.gnomish.domain.engine

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
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * Reference end-to-end in-memory run, task 7.3 (success metric M1). One task is driven
 * through a four-stage pipeline entirely with fakes, exercising in a single coherent
 * story every semantic the engine promises:
 *
 * <ul>
 *   <li><b>all four verify-check types</b> — {@code judge} (design and review),
 *       {@code command} + {@code builtin} (build), {@code external} poll loop (ci);</li>
 *   <li><b>a decision escalation and a decision-carrying resume</b> — the design stage's
 *       executor first returns {@code DecisionNeeded}; the caller appends the human answer
 *       to {@code TaskContext.decisions} and resumes, and that decision reaches the executor;</li>
 *   <li><b>a quality-failure retry with feedback</b> — the build stage fails its command
 *       check once, then the retry request carries that failure's findings forward and passes;</li>
 *   <li><b>a manual pause</b> — the final review stage is a {@code manual} checkpoint, so the
 *       run ends {@code Paused} at the pipeline end.</li>
 * </ul>
 *
 * <p>The narrative mirrors real use (proposal U1/U4): a run yields control on the decision
 * escalation, a human supplies the answer, and a second run drives the rest to the manual
 * checkpoint. Nothing here touches a tracker, filesystem, or git — only the seven ports.
 *
 * <p>Implements the M1 reference run of add-stage-engine (FR1–FR8). The fakes-only,
 * no-I/O run also demonstrates NFR-S1: the engine executes nothing itself.
 */
class ReferenceRunSpec extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def SECOND = Duration.ofSeconds(1)
    static final def TIMEOUT = Duration.ofSeconds(60)

    static VerifyCheck.Builtin builtin(String name) {
        new VerifyCheck.Builtin(name, [:])
    }

    static VerifyCheck.Command command(String line) {
        new VerifyCheck.Command(line)
    }

    static VerifyCheck.External external(String checkId) {
        new VerifyCheck.External(checkId, SECOND, TIMEOUT)
    }

    static VerifyCheck.Judge judge(int votes) {
        new VerifyCheck.Judge('criteria.md', 'judge-model', [:], votes)
    }

    static StageDefinition stage(String name, AdvancementMode advancement, List<VerifyCheck> verify) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(3), advancement)
    }

    // design(judge, auto) → build(command+builtin, auto) → ci(external, auto) → review(judge, manual)
    static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            stage('design', AdvancementMode.AUTO, [judge(1)]),
            stage('build', AdvancementMode.AUTO, [
                command('./gradlew test'),
                builtin('files_exist')
            ]),
            stage('ci', AdvancementMode.AUTO, [external('ci/gate')]),
            stage('review', AdvancementMode.MANUAL, [judge(1)])
        ])
    }

    static ExecutionResult.Completed completed(String stageName) {
        new ExecutionResult.Completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', stageName, 0), []))
    }

    static Verdict.Fail fail(String message) {
        new Verdict.Fail([
            new Finding(message, null, null)
        ])
    }

    static EnginePorts portsFor(ScriptedExecutor executor, ScriptedBuiltinCheckRunner builtinRunner,
            ScriptedCommandCheckRunner commandRunner, ScriptedExternalCheckClient externalClient,
            ScriptedJudgeVoter judgeVoter) {
        def clock = new VirtualClock()
        new EnginePorts(executor, builtinRunner, commandRunner, externalClient, judgeVoter,
                new RecordingEventListener(), new InMemoryAttemptPersistence(), clock, new VirtualSleeper(clock))
    }

    // M1: the whole story in two runs — decision escalation, decision-carrying resume,
    //     all four check types, a quality retry with feedback, and a manual pause.
    def "a task is driven end to end through all four check types to a manual pause"() {
        given: 'a four-stage pipeline and a task with no decisions yet'
        def pipeline = pipeline()
        def context0 = new TaskContext('TASK-1', 'add a feature', 'body', [])

        and: 'run-1 fakes: the design executor asks a human instead of completing'
        def exec1 = new ScriptedExecutor([
            new ExecutionResult.DecisionNeeded('which db?', ['postgres', 'mysql'],
            ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'design', 0), []))
        ])
        def ports1 = portsFor(exec1, new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter())

        when: 'run 1 drives the design stage'
        def outcome1 = new Engine().run(pipeline, context0, TaskState.atStageStart('design'), WORKSPACE, ports1)

        then: 'the run escalates the decision without burning an attempt and stays at design'
        outcome1 instanceof TaskOutcome.Escalated
        def report = outcome1.report() as EscalationReport.DecisionNeeded
        report.question() == 'which db?'
        report.options() == ['postgres', 'mysql']
        outcome1.finalState().attemptsUsed() == 0

        when: 'the human answers: the caller appends the decision and resumes from the design stage'
        def decision = new Decision('use postgres', 'design', 'alice', Instant.parse('2026-07-16T00:00:00Z'))
        def context1 = new TaskContext('TASK-1', 'add a feature', 'body', [decision])

        and: 'run-2 fakes scripted for the rest of the pipeline'
        // design: executor completes, judge passes
        // build: attempt 0 command FAILS (chain stops before builtin), attempt 1 command+builtin PASS
        // ci: executor completes, external polls Running then Pass
        // review: executor completes, judge passes, manual pause
        def exec2 = new ScriptedExecutor([
            completed('design'),
            completed('build'),
            completed('build'),
            completed('ci'),
            completed('review')
        ])
        def builtin2 = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        def command2 = new ScriptedCommandCheckRunner([
            fail('tests red'),
            new Verdict.Pass()
        ])
        def external2 = new ScriptedExternalCheckClient([
            new PollStatus.Running(),
            new PollStatus.Pass()
        ])
        def judge2 = new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.Pass(), [:]),
            new JudgeVoter.Vote(new Verdict.Pass(), [:])
        ])
        def persistence2 = new InMemoryAttemptPersistence()
        def clock2 = new VirtualClock()
        def sleeper2 = new VirtualSleeper(clock2)
        def ports2 = new EnginePorts(exec2, builtin2, command2, external2, judge2,
                new RecordingEventListener(), persistence2, clock2, sleeper2)

        and: 'run 2 resumes from the recorded design state'
        def outcome2 = new Engine().run(pipeline, context1, outcome1.finalState(), WORKSPACE, ports2)

        then: 'the run ends Paused at the manual review checkpoint, parked at the pipeline end'
        outcome2 instanceof TaskOutcome.Paused
        (outcome2 as TaskOutcome.Paused).passedStage() == 'review'
        outcome2.finalState().position() instanceof Position.PipelineEnd

        and: 'the human decision reached the design executor request verbatim (decision-carrying resume)'
        def designRequests = exec2.requests.findAll { it.stage().name() == 'design' }
        designRequests.size() == 1
        designRequests[0].context().decisions() == [decision]

        and: 'the build stage retried: attempt 0 then attempt 1, the retry carrying the command failure as feedback'
        def buildRequests = exec2.requests.findAll { it.stage().name() == 'build' }
        buildRequests.size() == 2
        buildRequests[0].attempt() == 0
        buildRequests[1].attempt() == 1
        buildRequests[1].feedback().size() == 1
        (buildRequests[1].feedback()[0].verdict() as Verdict.Fail).findings()[0].message() == 'tests red'

        and: 'all four check types were exercised'
        judge2.voteCount == 2                       // design + review
        command2.calls.size() == 2                  // build attempt 0 (fail) + attempt 1 (pass)
        builtin2.calls.size() == 1                  // build attempt 1 only (attempt 0 stopped at the failing command)
        external2.pollCount == 2                     // ci: Running then Pass

        and: 'the external poll loop slept the manifest interval once between its two polls'
        sleeper2.slept == [SECOND]

        and: 'every executed round of run 2 was persisted (design, build x2, ci, review = 5)'
        persistence2.entries.size() == 5
        persistence2.entries.every { it.taskId == 'TASK-1' }

        and: 'every persisted round carries a startedAt begin instant read from the Clock (FR15)'
        // FR15: each recorded round is stamped with the Clock reading taken when it began; with the
        //     VirtualClock starting at EPOCH and only the ci poll loop advancing it, every round begins
        //     at or after EPOCH and no round is left without a begin instant.
        persistence2.entries.each { entry ->
            entry.state.attempts().every { it.startedAt() != null && !it.startedAt().isBefore(Instant.EPOCH) }
        }
    }
}
