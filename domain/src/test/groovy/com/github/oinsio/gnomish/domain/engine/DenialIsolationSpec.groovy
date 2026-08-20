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
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * FR2, FR3 of fix-denial-report-attachment: a round's egress denials reach that
 * round's {@link AttemptRecord} and change nothing else. A denial is
 * observability, not a gate (proposal NG3), so the two ways it could leak into
 * an outcome are pinned shut here: it must not turn a passing attempt into a
 * failing one, and it must not enter the prior-failure feedback a retry carries
 * back to the executor.
 *
 * <p>The scenario the pre-change model could not represent at all is the first
 * one — a denial on an attempt whose every check passed had nowhere to live,
 * since findings entered the record only through a check {@code Verdict.Fail}.
 */
class DenialIsolationSpec extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def CONTEXT = new TaskContext('TASK-1', 'title', 'body', [])
    static final def DENIAL = new Finding(
    'egress denied: paste.example.com:443', 'paste.example.com:443/upload', 'kind=http method=POST')

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def persistence = new InMemoryAttemptPersistence()
    def listener = new RecordingEventListener()
    def clock = new VirtualClock()
    def sleeper = new VirtualSleeper(clock)

    EnginePorts ports() {
        new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), listener, persistence, clock, sleeper)
    }

    static StageDefinition stage(List<VerifyCheck> verify) {
        new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    static ExecutionResult.Completed completed(List<Finding> denials) {
        new ExecutionResult.Completed(
                ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []), denials)
    }

    static ExecutionResult.DecisionNeeded decisionNeeded(List<Finding> denials) {
        new ExecutionResult.DecisionNeeded(
                'which db?', ['postgres', 'mysql'], ExecutorUsage.none(),
                new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []), denials)
    }

    /** The attempts of the last state persisted before the stage advanced away from it. */
    private List<AttemptRecord> recordedAttempts() {
        persistence.entries.last().state.attempts()
    }

    // FR2, NFR-O1: the scenario the old model could not express — a denied round whose checks
    //     all passed. The attempt stays PASSED and the denial is still on the record.
    def "FR2: a denied round with all-pass checks records PASSED and keeps the denial"() {
        given: 'a passing check and a round whose environment recorded one denial'
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed([DENIAL])

        when:
        def outcome = new Engine().run(pipeline(), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the stage passed — the denial gated nothing (proposal NG3)'
        outcome instanceof TaskOutcome.Completed

        and: 'the recorded round is PASSED and carries the denial beside its passing check'
        def attempt = recordedAttempts().last()
        attempt.result() == AttemptRecord.Result.PASSED
        attempt.checkResults()*.verdict().every { it instanceof Verdict.Pass }
        attempt.denials() == [DENIAL]
    }

    // UX2: a quiet round reports an empty list, never a fabricated entry
    def "UX2: a round with no denials records an empty list"() {
        given:
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed([])

        when:
        new Engine().run(pipeline(), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then:
        recordedAttempts().last().denials() == []
    }

    // FR3, task 3.2: the decision round is the OTHER shape of executed round — it runs no verify
    //     chain, so its record has no check results at all, and the denial must ride it anyway
    def "FR3: a decision round records DECISION_NEEDED and keeps the denial"() {
        given: 'a round whose executor asks a human, after its environment denied an egress attempt'
        executor.scripted << decisionNeeded([DENIAL])

        when:
        def outcome = new Engine().run(pipeline(), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the run escalated for the decision — the denial gated nothing (proposal NG3)'
        outcome instanceof TaskOutcome.Escalated
        outcome.report() instanceof EscalationReport.DecisionNeeded

        and: 'the recorded round carries the denial beside its empty check list'
        def attempt = recordedAttempts().last()
        attempt.result() == AttemptRecord.Result.DECISION_NEEDED
        attempt.checkResults().isEmpty()
        attempt.denials() == [DENIAL]

        and: 'no verify check ran — the denial did not conjure one'
        builtinRunner.calls.isEmpty()
    }

    // FR2: the retry's feedback is assembled from checkResults only — a denial is not a finding
    //     the gnome is asked to fix, and feeding it back would put the guard in the loop
    def "FR2: denials never enter the prior-failure feedback of a retry"() {
        given: 'a first round that denies AND fails its check, then a passing retry'
        builtinRunner.scripted << new Verdict.Fail([
            new Finding('the real check finding', null, null)
        ])
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed([DENIAL])
        executor.scripted << completed([])

        when:
        new Engine().run(pipeline(), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the retry was asked to fix the check finding — and only that'
        def retryFeedback = executor.requests.last().feedback()
        retryFeedback.collectMany {
            ((Verdict.Fail) it.verdict()).findings()
        }*.message() == ['the real check finding']

        and: 'the denial still rode the first attempt into the record (FR3)'
        recordedAttempts().first().denials() == [DENIAL]
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            stage([
                new VerifyCheck.Builtin('files_exist', [:])
            ])
        ])
    }
}
