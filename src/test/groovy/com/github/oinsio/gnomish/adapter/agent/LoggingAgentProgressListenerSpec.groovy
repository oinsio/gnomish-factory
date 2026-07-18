package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * LoggingAgentProgressListener: an AgentProgressListener adapter that logs one
 * structured INFO line per AgentProgressEvent (task 8.1, design D10). Each spec
 * attaches a Logback ListAppender directly to the listener's own logger for the
 * duration of one onProgress call and asserts on the captured event's level and
 * formatted message — the same technique LoggingEventListenerSpec established
 * for asserting on logged output in this codebase.
 *
 * Implements FR7, NFR-O1, UX1, D10 of add-agent-executor.
 */
class LoggingAgentProgressListenerSpec extends Specification {

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(LoggingAgentProgressListener)
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

    // FR7, UX1: RoundStarted logs one INFO line naming model and sessionId
    def "RoundStarted logs one INFO line naming model and sessionId"() {
        given:
        def listener = new LoggingAgentProgressListener()

        when:
        def events = capture {
            listener.onProgress(new AgentProgressEvent.RoundStarted('claude-fake-main-1', 'fake-session-plain-1'))
        }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('round started')
        events[0].formattedMessage.contains('claude-fake-main-1')
        events[0].formattedMessage.contains('fake-session-plain-1')
    }

    // FR7, UX1: ToolStarted logs one INFO line naming the tool
    def "ToolStarted logs one INFO line naming the tool"() {
        given:
        def listener = new LoggingAgentProgressListener()

        when:
        def events = capture { listener.onProgress(new AgentProgressEvent.ToolStarted('Write')) }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('tool started')
        events[0].formattedMessage.contains('Write')
    }

    // FR7, UX1: RoundFinished logs one INFO line naming the summary
    def "RoundFinished logs one INFO line naming the summary"() {
        given:
        def listener = new LoggingAgentProgressListener()

        when:
        def events = capture {
            listener.onProgress(new AgentProgressEvent.RoundFinished('success', [:], 'Stage complete: output.txt written.'))
        }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('round finished')
        events[0].formattedMessage.contains('Stage complete: output.txt written.')
    }

    // FR7: RoundFinished with an empty summary still logs one INFO line
    def "RoundFinished with an empty summary still logs one INFO line"() {
        given:
        def listener = new LoggingAgentProgressListener()

        when:
        def events = capture { listener.onProgress(new AgentProgressEvent.RoundFinished(null, [:], '')) }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('round finished')
    }
}
