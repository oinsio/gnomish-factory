package com.github.oinsio.gnomish.status

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * LoggingEventListener: an EngineEventListener adapter that logs one structured INFO line per
 * EngineEvent to the rolling file appender (design D9). Each spec attaches a Logback ListAppender
 * directly to the listener's own logger for the duration of one onEvent call and asserts on the
 * captured event's level and formatted message — the same technique LoggingLevelSpec already
 * established for asserting on logged output in this codebase.
 *
 * Implements NFR-O2 of add-manual-run.
 */
class LoggingEventListenerSpec extends Specification {

    private static final String TASK_ID = 'manual-20260716-143502-x7'

    private static AttemptKey key(int attempt = 2, String stage = 'implement') {
        new AttemptKey(TASK_ID, stage, attempt)
    }

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(LoggingEventListener)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    // NFR-O2: RunStarted logs one INFO line naming position and attemptsUsed
    def "RunStarted logs one INFO line"() {
        given:
        def listener = new LoggingEventListener()

        when:
        def events = capture { listener.onEvent(new EngineEvent.RunStarted(TASK_ID, new Position.AtStage('implement'), 1)) }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('run started')
        events[0].formattedMessage.contains('1')
    }

    // NFR-O2: AttemptStarted logs one INFO line
    def "AttemptStarted logs one INFO line"() {
        given:
        def listener = new LoggingEventListener()

        when:
        def events = capture { listener.onEvent(new EngineEvent.AttemptStarted(key())) }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('attempt started')
    }

    // NFR-O2: ExecutionFinished logs one INFO line naming wallTime/tokens
    def "ExecutionFinished logs one INFO line"() {
        given:
        def listener = new LoggingEventListener()

        when:
        def events = capture { listener.onEvent(new EngineEvent.ExecutionFinished(key(), ExecutorUsage.none())) }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('execution finished')
    }

    // NFR-O2: CheckStarted logs one INFO line naming the check's label
    def "CheckStarted logs one INFO line"() {
        given:
        def listener = new LoggingEventListener()
        def check = new CheckRef(0, 'builtin:files_exist')

        when:
        def events = capture { listener.onEvent(new EngineEvent.CheckStarted(key(), check)) }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('check started')
        events[0].formattedMessage.contains('builtin:files_exist')
    }

    // NFR-O2: CheckFinished logs one INFO line naming the check's label and verdict kind
    def "CheckFinished logs one INFO line"() {
        given:
        def listener = new LoggingEventListener()
        def result = new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), java.time.Duration.ofMillis(3))

        when:
        def events = capture { listener.onEvent(new EngineEvent.CheckFinished(key(), result)) }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('check finished')
        events[0].formattedMessage.contains('builtin:files_exist')
        events[0].formattedMessage.contains('Pass')
    }

    // NFR-O2: AttemptFinished logs one INFO line naming attemptsUsed
    def "AttemptFinished logs one INFO line"() {
        given:
        def listener = new LoggingEventListener()
        def newState = TaskState.atStageStart('implement')

        when:
        def events = capture {
            listener.onEvent(new EngineEvent.AttemptFinished(key(), newState, new ToolTrace(key(), [])))
        }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('attempt finished')
        events[0].formattedMessage.contains('attemptsUsed=0')
    }

    // NFR-O2: TaskFinished logs one INFO line naming the outcome kind
    def "TaskFinished logs one INFO line"() {
        given:
        def listener = new LoggingEventListener()
        def outcome = new TaskOutcome.Completed(TaskState.atStageStart('implement'))

        when:
        def events = capture { listener.onEvent(new EngineEvent.TaskFinished(TASK_ID, outcome)) }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('task finished')
        events[0].formattedMessage.contains('Completed')
    }
}
