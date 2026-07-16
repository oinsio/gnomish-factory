package com.github.oinsio.gnomish.domain.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import org.slf4j.LoggerFactory

/**
 * Broken-listener resilience, task 6.2 — a listener that throws on EVERY event never
 * breaks a non-trivial run driven through {@code Engine.run} (FR12). The event stream is
 * observability, never an effect: the shared {@code Events.emit} helper swallows and
 * logs each listener failure at WARN (NFR-O1), so a throwing listener changes nothing
 * observable in the terminal outcome, the persisted states, or the executor/check port
 * calls — and every event is still delivered (the fake records before it throws).
 *
 * <p>Implements FR12, NFR-O1 of add-stage-engine.
 */
class BrokenListenerSpec extends BrokenListenerSpecBase {

    // FR12: run the non-trivial pipeline twice with identical fresh fakes — once with a
    //       silent listener (control), once with throwOnEvent = true — and prove the
    //       throwing listener changed NOTHING observable: same terminal outcome, same
    //       persisted states, same executor requests, same check calls.
    def "a throwing listener changes nothing observable in the run outcome or port calls"() {
        given: 'a control run with a silent recording listener'
        def controlExec = new ScriptedExecutor()
        def controlBuiltin = new ScriptedBuiltinCheckRunner()
        def controlPersist = new InMemoryAttemptPersistence()
        def controlListener = new RecordingEventListener()
        scriptFailThenPass(controlExec, controlBuiltin)

        and: 'a broken run with an identical fresh set of fakes and a listener that throws on every event'
        def brokenExec = new ScriptedExecutor()
        def brokenBuiltin = new ScriptedBuiltinCheckRunner()
        def brokenPersist = new InMemoryAttemptPersistence()
        def brokenListener = new RecordingEventListener(throwOnEvent: true)
        scriptFailThenPass(brokenExec, brokenBuiltin)

        when: 'both runs are driven end to end from the same starting state'
        def controlOutcome = new Engine().run(
                pipeline(stage('build', [builtin('files_exist')])), CONTEXT,
                TaskState.atStageStart('build'), WORKSPACE,
                portsFor(controlExec, controlBuiltin, controlPersist, controlListener))
        def brokenOutcome = new Engine().run(
                pipeline(stage('build', [builtin('files_exist')])), CONTEXT,
                TaskState.atStageStart('build'), WORKSPACE,
                portsFor(brokenExec, brokenBuiltin, brokenPersist, brokenListener))

        then: 'the control run completed — the pipeline is genuinely non-trivial'
        controlOutcome instanceof TaskOutcome.Completed

        and: 'the broken run reached the SAME terminal outcome, byte for byte'
        brokenOutcome == controlOutcome

        and: 'the persisted states are identical — the throwing listener changed no durable state'
        brokenPersist.entries*.state == controlPersist.entries*.state

        and: 'the executor saw the same requests and the check runner the same calls'
        brokenExec.requests == controlExec.requests
        brokenBuiltin.calls == controlBuiltin.calls
    }

    // FR12: the throwing listener still RECORDED every event (it records before it throws),
    //       in the same count and class sequence as the silent control — proving the engine
    //       kept emitting subsequent events after each throw rather than aborting the stream.
    def "every event is still delivered despite the listener throwing on each one"() {
        given: 'a control run with a silent listener and a broken run with an identical fresh set'
        def controlExec = new ScriptedExecutor()
        def controlBuiltin = new ScriptedBuiltinCheckRunner()
        def controlListener = new RecordingEventListener()
        scriptFailThenPass(controlExec, controlBuiltin)

        def brokenExec = new ScriptedExecutor()
        def brokenBuiltin = new ScriptedBuiltinCheckRunner()
        def brokenListener = new RecordingEventListener(throwOnEvent: true)
        scriptFailThenPass(brokenExec, brokenBuiltin)

        when: 'both runs are driven'
        new Engine().run(pipeline(stage('build', [builtin('files_exist')])), CONTEXT,
        TaskState.atStageStart('build'), WORKSPACE,
        portsFor(controlExec, controlBuiltin, new InMemoryAttemptPersistence(), controlListener))
        new Engine().run(pipeline(stage('build', [builtin('files_exist')])), CONTEXT,
        TaskState.atStageStart('build'), WORKSPACE,
        portsFor(brokenExec, brokenBuiltin, new InMemoryAttemptPersistence(), brokenListener))

        then: 'the broken listener recorded the SAME event class sequence as the control'
        brokenListener.events*.getClass() == controlListener.events*.getClass()

        and: 'and the full stream was delivered — both bookends plus the two-round choreography'
        brokenListener.events*.getClass() == [
            EngineEvent.RunStarted,
            EngineEvent.AttemptStarted,
            EngineEvent.ExecutionFinished,
            EngineEvent.CheckStarted,
            EngineEvent.CheckFinished,
            EngineEvent.AttemptFinished,
            EngineEvent.AttemptStarted,
            EngineEvent.ExecutionFinished,
            EngineEvent.CheckStarted,
            EngineEvent.CheckFinished,
            EngineEvent.AttemptFinished,
            EngineEvent.TaskFinished,
        ]
    }

    // NFR-O1: each swallowed listener failure is logged at WARN by Events, at the point of
    //         capture. A Logback ListAppender attached to the Events logger captures the
    //         warnings; there is exactly one WARN per emitted engine event, so the run is
    //         unaffected AND the failure is faithfully recorded rather than silently dropped.
    def "each swallowed listener failure is logged at WARN"() {
        given: 'a ListAppender attached to the Events logger'
        Logger eventsLogger = (Logger) LoggerFactory.getLogger(Events)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        eventsLogger.addAppender(appender)

        and: 'the non-trivial fail-then-pass run with a listener that throws on every event'
        def executor = new ScriptedExecutor()
        def builtinRunner = new ScriptedBuiltinCheckRunner()
        def listener = new RecordingEventListener(throwOnEvent: true)
        scriptFailThenPass(executor, builtinRunner)

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stage('build', [builtin('files_exist')])), CONTEXT,
        TaskState.atStageStart('build'), WORKSPACE,
        portsFor(executor, builtinRunner, new InMemoryAttemptPersistence(), listener))

        then: 'the run completed unaffected'
        outcome instanceof TaskOutcome.Completed

        and: 'exactly one WARN was logged per emitted event — the swallow-and-log path fired for each'
        def warnings = appender.list.findAll { it.level == Level.WARN }
        warnings.size() == listener.events.size()

        and: 'every captured line is a WARN naming a listener failure'
        warnings.every { it.level == Level.WARN && it.formattedMessage.contains('listener threw') }

        cleanup:
        eventsLogger.detachAppender(appender)
    }
}
