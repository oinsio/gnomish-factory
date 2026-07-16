package com.github.oinsio.gnomish.domain.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * StageAttemptLoop persist-failure abort behavior, task 5.4 — a thrown persist ends the run as
 * Aborted(failedAt, cause) with the round's key and the preserved stack trace, emitting no
 * AttemptFinished for that round and starting no further attempt (FR11, NFR-O1).
 *
 * <p>Happy-path ordering lives in {@link PersistenceOrderingSpec}; the shared fixture lives in
 * {@link PersistenceOrderingSpecBase}.
 *
 * <p>Implements FR11, FR13, NFR-O1 of add-stage-engine.
 */
class PersistenceAbortSpec extends PersistenceOrderingSpecBase {

    // FR11 (persistence failure aborts): persist throwing on the round ends the run as
    //     Aborted(failedAt, cause) with the round's key and a non-blank cause; no AttemptFinished
    //     for that round and no further executor call.
    def "aborts the run when persist throws on the round"() {
        given: 'a CannotVerify round whose persist throws on the first call'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << new Verdict.CannotVerify('binary not found', 'no such tool')
        executor.scripted << completed()
        persistence.failOnCall = 1

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Aborted at the round key with a non-blank cause'
        outcome instanceof TaskOutcome.Aborted
        def aborted = outcome as TaskOutcome.Aborted
        aborted.failedAt() == new AttemptKey('TASK-1', 'build', 0)
        !aborted.cause().isBlank()

        and: 'no AttemptFinished was emitted for the aborted round'
        listener.events.every { !(it instanceof EngineEvent.AttemptFinished) }

        and: 'no further executor call happened after the failed round'
        executor.requests.size() == 1
    }

    // FR11 (persist failure aborts mid-retry): persist throwing on round N ends the run at that
    //     round; the prior round persisted normally and no further attempt starts.
    def "aborts on a later round without starting the next attempt"() {
        given: 'a stage that fails once then cannot verify, with persist failing on the second call'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << fail('findingA')
        builtinRunner.scripted << new Verdict.CannotVerify('binary not found', 'no such tool')
        executor.scripted << completed()
        executor.scripted << completed()
        persistence.failOnCall = 2

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Aborted at round 1'
        outcome instanceof TaskOutcome.Aborted
        (outcome as TaskOutcome.Aborted).failedAt() == new AttemptKey('TASK-1', 'build', 1)

        and: 'both rounds were executed but only one AttemptFinished fired — the aborted round emitted none'
        executor.requests.size() == 2
        listener.events.findAll { it instanceof EngineEvent.AttemptFinished }.size() == 1
    }

    // NFR-O1: on a persist failure the Aborted cause carries the thrown exception's stack trace,
    //     the same text logged at ERROR at the point of capture.
    def "carries the persist failure's stack trace into the Aborted cause"() {
        given: 'a CannotVerify round whose persist throws'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << new Verdict.CannotVerify('binary not found', 'no such tool')
        executor.scripted << completed()
        persistence.failOnCall = 1

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the Aborted cause contains the rendered stack trace of the persist failure'
        def cause = (outcome as TaskOutcome.Aborted).cause()
        cause.contains('java.lang.RuntimeException')
        cause.contains('persist failed on call 1')
        cause.contains('at ')
    }

    // NFR-O1: the caught persist failure is logged at ERROR at the point of capture, naming the
    //     round key — asserted via a Logback ListAppender on the AttemptJournal logger.
    def "logs the persist failure at ERROR at the point of capture"() {
        given: 'a ListAppender attached to the AttemptJournal logger'
        Logger journalLogger = (Logger) LoggerFactory.getLogger(AttemptJournal)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        journalLogger.addAppender(appender)

        and: 'a round whose persist throws'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << new Verdict.CannotVerify('binary not found', 'no such tool')
        executor.scripted << completed()
        persistence.failOnCall = 1

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'exactly one ERROR line was logged, naming the persist failure'
        def errors = appender.list.findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].formattedMessage.contains('persist failed')

        cleanup:
        journalLogger.detachAppender(appender)
    }
}
