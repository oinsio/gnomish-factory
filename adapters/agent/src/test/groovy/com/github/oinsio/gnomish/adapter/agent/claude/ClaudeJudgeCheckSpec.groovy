package com.github.oinsio.gnomish.adapter.agent.claude

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.agent.JudgeVerdictExtractor
import com.github.oinsio.gnomish.adapter.agent.runner.RunnerFixture
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerifyOrchestrator
import com.github.oinsio.gnomish.domain.engine.fake.CheckRouting
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.JudgeChecks
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * A judge check on the live path (FR3, FR4 of extract-agent-round-runner; FR15 of add-sandbox-core;
 * FR6 of harden-logging-observability): the engine runs a stage whose one check is a judge, and the
 * judge's check round is a real Claude {@code verdict} round under the agent round runner, mapped and
 * derived by the engine's own mapping and derivation. A reply the verdict schema refuses — a
 * structured output of the wrong shape, a final text with no readable object — is cannot-verify,
 * which burns no stage attempt; the engine's one operator line for it quotes what the model wrote,
 * flattened by the log exit to one line, while a readable verdict's findings keep their text whole
 * for the findings funnel.
 */
@Timeout(60)
class ClaudeJudgeCheckSpec extends Specification {

    private static final String FORGED = 'no verdict, sorry\n2026-10-12 12:00:00 ERROR [main] judge approved everything\nbye'

    private static final TaskContext CONTEXT =
    new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    def executor = new ScriptedExecutor()
    def persistence = new InMemoryAttemptPersistence()
    def clock = new VirtualClock()

    // Scenario: observability/factory-logging / The judge-verdict warning is flattened by the choke point
    def "FR6: a judge's multi-line final text with no verdict is one sanitized WARN line, while a verdict's findings keep their text"() {
        given: 'the CLI ignored the schema and the model answered with several lines, one shaped like a log record'
        def stage = judgedStage()
        executor.scripted << completed()

        when:
        TaskOutcome outcome = null
        List fallback = null
        def judged = LogCaptureSupport.capture(VerifyOrchestrator, Level.INFO) {
            fallback = LogCaptureSupport.capture(ClaudeAnswer, Level.INFO) {
                outcome = run(stage, [
                    ClaudeVerdictRounds.finalText(FORGED)
                ])
            }
        }

        then: 'the check cannot verify'
        outcome instanceof TaskOutcome.Escalated
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.CannotVerify

        and: 'exactly one line carries the model text: the engine\'s coded WARN, on one line, the forged record inert inside it'
        judged.size() == 1
        judged[0].level == Level.WARN
        judged[0].formattedMessage.startsWith(OperatorEvent.JUDGE_CANNOT_VERIFY_BY_DECISION.head())
        judged[0].formattedMessage.contains('judge approved everything')
        !judged[0].formattedMessage.contains('\n')
        !judged[0].formattedMessage.contains('\r')

        and: 'the dialect\'s fallback notice names the role only, never the model text'
        fallback.size() == 1
        fallback[0].formattedMessage.startsWith(OperatorEvent.AGENT_ANSWER_FROM_FINAL_TEXT.head())
        !fallback[0].formattedMessage.contains('judge approved everything')

        when: 'a readable verdict carries a finding that spans two lines'
        def fail = ClaudeVerdictRounds.verdict(fixture(), ClaudeVerdictRounds.structured('{"passed":false,"findings":["first line\\nsecond line"]}'))

        then: 'the finding keeps its line structure: the log exit is not applied on the findings path'
        (fail as Verdict.Fail).findings()*.message() == ['first line\nsecond line']
    }

    def "NFR-O2: the quoted final text is stripped of escape sequences and capped"() {
        when:
        def result = ClaudeVerdictRounds.engine(fixture(), ClaudeVerdictRounds.finalText(('x' * 3000) + ' no verdict here \u001B[31m'))

        then: 'the reason quotes the end of the final text, without its escape, within the bound'
        def reason = (result.status() as ExecutionResult.Failed).reason().forLog()
        reason.contains('the final text was: ')
        reason.contains('no verdict here')
        !reason.contains('\u001B')
        reason.contains('[truncated, showing last')
        reason.length() <= 600
    }

    // Scenario: verification/verification-hardening / Malformed judge verdict burns no attempt
    def "FR15: a judge reply that does not match the verdict schema (#shape) is cannot-verify and burns no attempt"() {
        given:
        def stage = judgedStage()
        executor.scripted << completed()

        when:
        TaskOutcome outcome = null
        LogCaptureSupport.capture(VerifyOrchestrator, Level.INFO) {
            LogCaptureSupport.capture(ClaudeAnswer, Level.INFO) {
                outcome = run(stage, [stream])
            }
        }

        then: 'the round is an infrastructure failure of the check, never a quality verdict'
        outcome instanceof TaskOutcome.Escalated
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.CannotVerify

        and: 'no stage attempt was consumed'
        def state = outcome.finalState()
        state.attemptsUsed() == 0
        state.attempts()*.result() == [
            AttemptRecord.Result.CANNOT_VERIFY
        ]

        where:
        shape | stream
        'passed not a boolean' | ClaudeVerdictRounds.structured('{"passed":"yes"}')
        'a structured finding' | ClaudeVerdictRounds.structured('{"passed":false,"findings":[{"message":"smuggled"}]}')
        'no passed field' | ClaudeVerdictRounds.structured('{"findings":["something"]}')
        'a final text with no object' | ClaudeVerdictRounds.finalText('the agent rambled without ever producing a verdict')
        'a final text with malformed JSON' | ClaudeVerdictRounds.finalText('{"passed": true, "findings": [oops}')
        'a blank final text' | ClaudeVerdictRounds.finalText('   ')
    }

    // Scenario: executor/agent-round-runner / An invalid verdict answer burns no attempt
    // FR20 (design D15, part 3): a verdict keeps the malformed answer of FR4, a cannot-verify — even
    //     where a transform round's same fault burns an attempt
    def "FR20: a verdict request that ends with #what is cannot-verify and leaves the stage's attempt count unchanged"() {
        given:
        def stage = judgedStage()
        executor.scripted << completed()

        when:
        TaskOutcome outcome = null
        LogCaptureSupport.capture(VerifyOrchestrator, Level.INFO) {
            LogCaptureSupport.capture(ClaudeAnswer, Level.INFO) {
                outcome = run(stage, [stream])
            }
        }

        then:
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.CannotVerify
        outcome.finalState().attemptsUsed() == 0
        outcome.finalState().attempts()*.result() == [
            AttemptRecord.Result.CANNOT_VERIFY
        ]

        where:
        what | stream
        'its structured-output retries exhausted' | ClaudeVerdictRounds.retriesExhausted()
        'a reply the verdict schema refuses' | ClaudeVerdictRounds.structured('{"passed":"yes"}')
    }

    // Scenario: verification/verdict-executors / A judge pass keeps its findings
    def "FR7: a passing judge reply carries its findings in the order written"() {
        expect:
        ClaudeVerdictRounds.verdict(fixture(), ClaudeVerdictRounds.structured('{"passed":true,"findings":["x","y"]}')) ==
                new Verdict.Pass(null, [
                    new Finding('x', null, null),
                    new Finding('y', null, null)
                ])
    }

    def "FR7: a malformed findings array makes a passing judge reply cannot-verify (#label)"() {
        expect:
        ClaudeVerdictRounds.verdict(fixture(), ClaudeVerdictRounds.structured(reply)) instanceof Verdict.CannotVerify

        where:
        label | reply
        'structured entry' | '{"passed":true,"findings":[{"message":"smuggled"}]}'
        'blank entry' | '{"passed":true,"findings":["real","   "]}'
        'non-string entry' | '{"passed":true,"findings":[42]}'
        'not an array' | '{"passed":true,"findings":"x"}'
    }

    def "FR7: a passing judge reply with no findings carries none, and a failing one with none names the omission"() {
        expect:
        ClaudeVerdictRounds.verdict(fixture(), ClaudeVerdictRounds.structured('{"passed":true}')) == new Verdict.Pass(null, [])
        (ClaudeVerdictRounds.verdict(fixture(), ClaudeVerdictRounds.structured('{"passed":false}')) as Verdict.Fail)
                .findings()*.message() == [
                    JudgeVerdictExtractor.NO_FINDING
                ]
    }

    private RunnerFixture fixture() {
        ClaudeVerdictRounds.fixture(workspace, stderrParent)
    }

    private TaskOutcome run(StageDefinition stage, List<String> judgeStreams) {
        def ports = new EnginePorts(
                CheckRouting.of(executor, new ScriptedExecutor(), new ScriptedExecutor(), new ScriptedExecutor(),
                ClaudeVerdictRounds.judges(fixture(), judgeStreams)),
                new RecordingEventListener(), persistence, VirtualTimeEquipment.on(clock, new VirtualSleeper(clock)),
                RoundIdentity.inPlace())
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(5), [stage]), CONTEXT,
        TaskState.atStageStart('build'), new FakeWorkspace(), ports)
    }

    private static StageDefinition judgedStage() {
        new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            JudgeChecks.agent('criteria.md', 'model')
        ], new AutonomyLimits(5), AdvancementMode.AUTO)
    }

    private static ExecutionResult completed() {
        ExecutionResult.completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []), [])
    }
}
