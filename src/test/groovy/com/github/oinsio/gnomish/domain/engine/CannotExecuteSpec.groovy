package com.github.oinsio.gnomish.domain.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * StageAttemptLoop, task 5.7 — the executor-infrastructure-failure escalation path. When
 * the {@code StageExecutor} port throws (its own retries exhausted), the round never
 * completes: NO round is recorded, NO verify chain runs, NO state is persisted, and no
 * attempt is burned. The run escalates immediately as {@code Escalated(CannotExecute)}
 * carrying the executor failure's preserved stack trace, and the run's bookend events
 * (RunStarted, TaskFinished) still fire (FR10, NFR-O1).
 *
 * <p>Implements FR10, NFR-O1 of add-stage-engine.
 */
class CannotExecuteSpec extends Specification {

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

    static VerifyCheck.Builtin builtin(String name) {
        new VerifyCheck.Builtin(name, [:])
    }

    static StageDefinition stage(String name, int attemptLimit, List<VerifyCheck> verify) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(attemptLimit), AdvancementMode.AUTO)
    }

    static PipelineDefinition pipeline(StageDefinition... stages) {
        new PipelineDefinition('1', new AutonomyLimits(5), stages.toList())
    }

    static ExecutionResult.Completed completed() {
        new ExecutionResult.Completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []))
    }

    static Verdict.Fail fail(String message) {
        new Verdict.Fail([
            new Finding(message, null, null)
        ])
    }

    // FR10: the executor port throwing escalates as Escalated(CannotExecute) with NO round
    //     recorded, NO attempt burned, NO state persisted, and NO verify check ran.
    def "an executor throw escalates as CannotExecute without recording, burning or persisting"() {
        given: 'a stage whose executor throws instead of returning a result'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        executor.toThrow = new RuntimeException('provider 503')

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Escalated(CannotExecute)'
        outcome instanceof TaskOutcome.Escalated
        outcome.report() instanceof EscalationReport.CannotExecute

        and: 'no attempt was burned and no round was recorded'
        def finalState = outcome.finalState()
        finalState.attemptsUsed() == 0
        finalState.attempts().isEmpty()

        and: 'no state was persisted — the round never completed'
        persistence.entries.isEmpty()

        and: 'no verify check runner was invoked — the executor threw before any check'
        builtinRunner.calls.isEmpty()

        and: 'the executor was called exactly once'
        executor.requests.size() == 1
    }

    // NFR-O1: the CannotExecute report carries the thrown executor exception's rendered
    //     stack trace — the same text logged at ERROR at the point of capture.
    def "carries the executor failure's stack trace into the CannotExecute cause"() {
        given: 'a stage whose executor throws a distinctive exception'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        executor.toThrow = new RuntimeException('provider 503')

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the CannotExecute cause contains the rendered stack trace of the executor failure'
        def cause = (outcome.report() as EscalationReport.CannotExecute).cause()
        cause.contains('java.lang.RuntimeException')
        cause.contains('provider 503')
        cause.contains('at ')
    }

    // FR12: the run bookends always fire — RunStarted and TaskFinished are emitted even when
    //     the executor throws before any round completes.
    def "still emits RunStarted and TaskFinished when the executor throws"() {
        given: 'a stage whose executor throws'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        executor.toThrow = new RuntimeException('provider 503')

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'both run bookend events were emitted exactly once'
        listener.events.findAll { it instanceof EngineEvent.RunStarted }.size() == 1
        listener.events.findAll { it instanceof EngineEvent.TaskFinished }.size() == 1
    }

    // FR10: an executor throw on a LATER attempt (after a prior quality failure) escalates
    //     CannotExecute; only the prior Fail burned an attempt, and the CannotExecute round
    //     is NOT recorded or persisted — the failed round alone remains.
    def "an executor throw after a prior quality failure leaves only the burned Fail round"() {
        given: 'a stage that fails once (burning attempt 0) then the executor throws on the retry'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << fail('findingA')
        executor.scripted << completed()
        executor.throwOnCall = 2
        executor.toThrow = new RuntimeException('provider 503')

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Escalated(CannotExecute)'
        outcome instanceof TaskOutcome.Escalated
        outcome.report() instanceof EscalationReport.CannotExecute

        and: 'only the prior Fail burned an attempt — the CannotExecute round did not record'
        def finalState = outcome.finalState()
        finalState.attemptsUsed() == 1
        finalState.attempts().size() == 1
        finalState.attempts()[0].round() == 0

        and: 'only the failed round was persisted — the CannotExecute round was not'
        persistence.entries.size() == 1
        persistence.entries[0].state.attempts().size() == 1

        and: 'the executor was called twice — the completing round then the throwing retry'
        executor.requests.size() == 2
    }

    // NFR-O1: the caught executor throw is logged at ERROR at the point of capture, naming the
    //     round key — asserted via a Logback ListAppender on the RoundExecution logger.
    def "logs the caught executor throw at ERROR at the point of capture"() {
        given: 'a ListAppender attached to the RoundExecution logger'
        Logger roundLogger = (Logger) LoggerFactory.getLogger(RoundExecution)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        roundLogger.addAppender(appender)

        and: 'a stage whose executor throws'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        executor.toThrow = new RuntimeException('provider 503')

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'exactly one ERROR line was logged, naming the executor throw'
        def errors = appender.list.findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].formattedMessage.contains('executor threw')

        cleanup:
        roundLogger.detachAppender(appender)
    }
}
