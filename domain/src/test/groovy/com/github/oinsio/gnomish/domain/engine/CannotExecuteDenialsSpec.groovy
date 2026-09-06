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
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import spock.lang.Specification

/**
 * The failure channel of fix-denial-attribution-durability, end to end at the engine level: a
 * round whose executor throws an {@code ExecutorFailure} escalates as {@code CannotExecute}
 * carrying the denials the dead round's environment recorded — the round left no
 * {@code AttemptRecord} to hold them, so the escalation report is their only place to land
 * (FR1, NFR-O1, design D1).
 *
 * <p>The complementary "carrying them changes nothing" half is asserted here too: the attempt
 * count and history are those of the same failure without denials, and so is the escalation
 * text — the engine renders the wrapper's CAUSE, never the wrapper.
 *
 * <p>The classification, event and persistence guarantees of the path itself
 * ({@code CannotExecute} is still an infrastructure failure) live in {@code CannotExecuteSpec}
 * and are not repeated here.
 *
 * <p>Implements FR1, NFR-O1 of fix-denial-attribution-durability.
 */
class CannotExecuteDenialsSpec extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def CONTEXT = new TaskContext('TASK-1', 'title', 'body', [])

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
                listener, persistence, clock, sleeper)
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

    private TaskOutcome runWith(RuntimeException thrown) {
        executor.toThrow = thrown
        new Engine().run(pipeline(), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports()) as TaskOutcome
    }

    // FR1: a round killed before its close hands its drained denials out on the ExecutorFailure,
    //     and the engine copies them onto the escalation report the reviewer reads.
    def "an executor failure carrying denials escalates CannotExecute with those denials"() {
        given: 'a round the guard blocked twice before the executor gave up'
        def blockedPost = denial('egress denied: POST paste.example/api')
        def blockedGet = denial('egress denied: GET paste.example/raw')

        when: 'the executor throws the failure wrapping both denials'
        def outcome = runWith(new ExecutorFailure(new RuntimeException('provider 503'), [blockedPost, blockedGet]))

        then: 'the escalation is CannotExecute carrying both denials in read order'
        outcome instanceof TaskOutcome.Escalated
        def report = outcome.report() as EscalationReport.CannotExecute
        report.denials() == [blockedPost, blockedGet]
    }

    // FR1: carrying denials changes no classification — no attempt is burned and no round is
    //     recorded, exactly as for the same failure without them.
    def "denials on the escalation burn no attempt and record no round"() {
        when: 'the executor throws a failure carrying a denial'
        def outcome = runWith(new ExecutorFailure(new RuntimeException('provider 503'), [denial('egress denied')]))

        then: 'the attempt count and the attempt history are untouched'
        def finalState = outcome.finalState()
        finalState.attemptsUsed() == 0
        finalState.attempts().isEmpty()

        and: 'nothing was persisted — the round never completed'
        persistence.entries.isEmpty()
    }

    // FR1, design D1: the wrapper adds attribution, not a failure mode — the engine renders the
    //     CAUSE, so the escalation text is byte-identical to that of the bare failure.
    def "the escalation text is identical to the same failure thrown without denials"() {
        given: 'one and the same infrastructure failure, thrown bare and thrown wrapped'
        def original = new RuntimeException('provider 503')

        when: 'the bare failure is escalated'
        def bare = (runWith(original).report() as EscalationReport.CannotExecute)

        and: 'a fresh run escalates the same failure wrapped with a denial'
        executor = new ScriptedExecutor()
        persistence = new InMemoryAttemptPersistence()
        def wrapped = (runWith(new ExecutorFailure(original, [denial('egress denied')]))
        .report() as EscalationReport.CannotExecute)

        then: 'the rendered cause is the same text — the wrapper never reaches the report'
        wrapped.cause() == bare.cause()

        and: 'only the denials tell the two escalations apart'
        bare.denials().isEmpty()
        !wrapped.denials().isEmpty()
    }

    // NFR-O1: the ERROR line at the point of capture names the cause, not the wrapper — an
    //     operator reading the log sees the failure that ended the round.
    def "logs the wrapped cause, not the wrapper, at the point of capture"() {
        given: 'a capture on the round mechanics'
        def logs = LogCaptureSupport.attach(RoundExecution, Level.ERROR)

        when: 'the executor throws a failure carrying a denial'
        runWith(new ExecutorFailure(new RuntimeException('provider 503'), [denial('egress denied')]))

        then: 'exactly one ERROR was logged, and its throwable is the cause'
        def errors = logs.list.findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].formattedMessage.contains('executor threw')
        errors[0].throwableProxy.className == RuntimeException.name
        errors[0].throwableProxy.message == 'provider 503'

        cleanup:
        logs.detach()
    }
}
