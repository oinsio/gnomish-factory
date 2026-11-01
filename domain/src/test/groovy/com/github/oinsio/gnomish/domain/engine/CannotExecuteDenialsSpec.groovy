package com.github.oinsio.gnomish.domain.engine

import ch.qos.logback.classic.Level
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
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The failure channel of fix-denial-attribution-durability, end to end at the engine level: a
 * round whose executor reports {@code failed/infrastructure} (FR7 of define-executor-contract —
 * the agent round's timeout kill, task 7.4) escalates as {@code CannotExecute} carrying the
 * denials the dead round's environment recorded — the round left no {@code AttemptRecord} to hold
 * them, so the escalation report is their only place to land (FR1, NFR-O1, design D1).
 *
 * <p>The complementary "carrying them changes nothing" half is asserted here too: the attempt
 * count and history are those of the same failure without denials, and so is the escalation
 * text.
 *
 * <p>The classification, event and persistence guarantees of the path itself
 * ({@code CannotExecute} is still an infrastructure failure) live in {@code CannotExecuteSpec}
 * and are not repeated here.
 *
 * <p>Implements FR1, NFR-O1 of fix-denial-attribution-durability.
 */
class CannotExecuteDenialsSpec extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def commandRunner = new ScriptedCommandCheckRunner()
    def externalClient = new ScriptedExternalCheckClient()
    def judgeVoter = new ScriptedJudgeVoter()
    def persistence = new InMemoryAttemptPersistence()
    def listener = new RecordingEventListener()
    def clock = new VirtualClock()
    def sleeper = new VirtualSleeper(clock)

    EnginePorts ports() {
        new EnginePorts(executor, builtinRunner, commandRunner, externalClient, judgeVoter,
                listener, persistence, VirtualTimeEquipment.on(clock, sleeper), RoundIdentity.inPlace())
    }

    static StageDefinition stage() {
        new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [:])
        ],
        new AutonomyLimits(5), AdvancementMode.AUTO)
    }

    static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(5), [stage()])
    }

    static Denial denial(String message) {
        Denial.unidentified(new Finding(message, 'paste.example', null))
    }

    /** The result an agent round killed on its timeout reports: failed/infrastructure with its denials. */
    static ExecutionResult killed(String reason, List<Denial> denials) {
        new ExecutionResult(
                new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, UntrustedText.subprocess(reason), []),
                null, ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []), denials)
    }

    private TaskOutcome runWith(ExecutionResult reported) {
        executor.scripted << reported
        new Engine().run(pipeline(), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports()) as TaskOutcome
    }

    // FR1: a round killed before its close hands its drained denials out on its failed result,
    //     and the engine's classifier copies them onto the escalation report the reviewer reads.
    // Scenario: pipeline/stage-engine / A round killed before its close reports its denials
    def "an executor failure carrying denials escalates CannotExecute with those denials"() {
        given: 'a round the guard blocked twice before the executor gave up'
        def blockedPost = denial('egress denied: POST paste.example/api')
        def blockedGet = denial('egress denied: GET paste.example/raw')

        when: 'the executor reports the failure carrying both denials'
        def outcome = runWith(killed('round exceeded roundTimeout', [blockedPost, blockedGet]))

        then: 'the escalation is CannotExecute carrying both denials in read order'
        outcome instanceof TaskOutcome.Escalated
        def report = outcome.report() as EscalationReport.CannotExecute
        report.denials() == [blockedPost, blockedGet]
    }

    // FR1: carrying denials changes no classification — no attempt is burned and no round is
    //     recorded, exactly as for the same failure without them.
    // Scenario: git/git-task-persistence / A parked task keeps the hung round's denial
    // Scenario: pipeline/stage-engine / A round killed before its close reports its denials
    def "denials on the escalation burn no attempt and record no round"() {
        when: 'the executor reports a failure carrying a denial'
        def outcome = runWith(killed('round exceeded roundTimeout', [denial('egress denied')]))

        then: 'the attempt count and the attempt history are untouched'
        def finalState = outcome.finalState()
        finalState.attemptsUsed() == 0
        finalState.attempts().isEmpty()

        and: 'nothing was persisted — the round never completed'
        persistence.entries.isEmpty()
    }

    // FR1, design D1: the denials add attribution, not a failure mode — the escalation text is
    //     byte-identical to that of the same failure reported without them.
    // Scenario: pipeline/stage-engine / Denials of a failed round gate nothing
    def "the escalation text is identical to the same failure reported without denials"() {
        when: 'the failure is escalated without denials'
        def bare = (runWith(killed('provider 503', [])).report() as EscalationReport.CannotExecute)

        and: 'a fresh run escalates the same failure with a denial'
        executor = new ScriptedExecutor()
        persistence = new InMemoryAttemptPersistence()
        def attributed = (runWith(killed('provider 503', [denial('egress denied')]))
        .report() as EscalationReport.CannotExecute)

        then: 'the cause is the same text'
        attributed.cause() == bare.cause()

        and: 'only the denials tell the two escalations apart'
        bare.denials().isEmpty()
        !attributed.denials().isEmpty()
    }

    // NFR-O1: a reported failure is one WARN at the point of decision, naming the round and the
    //     reason; no ERROR, since nothing was thrown.
    def "a reported failure carrying denials is one WARN naming the round, and no ERROR"() {
        given: 'a capture on the round mechanics'
        def logs = LogCaptureSupport.attach(RoundFailures, Level.DEBUG)

        when: 'the executor reports a failure carrying a denial'
        runWith(killed('provider 503', [denial('egress denied')]))

        then: 'exactly one operator line, the reported-failure WARN'
        def operator = logs.list.findAll {
            it.level.isGreaterOrEqual(Level.WARN)
        }
        operator.size() == 1
        operator[0].level == Level.WARN
        operator[0].formattedMessage.startsWith(OperatorEvent.EXECUTOR_REPORTED_FAILURE.head())
        operator[0].formattedMessage.contains('provider 503')

        cleanup:
        logs.detach()
    }
}
