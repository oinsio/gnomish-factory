package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import spock.lang.Specification

/**
 * LoggingAgentProgressListener: an AgentProgressListener adapter that logs one
 * structured line per AgentProgressEvent (task 8.1, design D10) — the round's own boundaries at
 * INFO, the per-tool-call detail at DEBUG (FR12 of harden-logging-observability). Each spec
 * captures the listener's own logger for the duration of one onProgress call and asserts on the
 * captured event's level and formatted message.
 *
 * Implements FR7, NFR-O1, UX1, D10 of add-agent-executor.
 */
class LoggingAgentProgressListenerSpec extends Specification {

    /**
     * Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.4 touched this spec.
     * Pinned at DEBUG, which is where the per-tool-call line now lives.
     */
    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        def logs = LogCaptureSupport.attach(LoggingAgentProgressListener, Level.DEBUG)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
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

    // FR7, UX1 + FR12 of harden-logging-observability: the tool call is still on the record, but
    //     at DEBUG — a single round makes dozens of them and none is a state change of the run.
    def "ToolStarted logs one DEBUG line naming the tool"() {
        given:
        def listener = new LoggingAgentProgressListener()

        when:
        def events = capture {
            listener.onProgress(new AgentProgressEvent.ToolStarted('Write'))
        }

        then:
        events.size() == 1
        events[0].level == Level.DEBUG
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
        def events = capture {
            listener.onProgress(new AgentProgressEvent.RoundFinished(null, [:], ''))
        }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('round finished')
    }

    // FR6 of harden-logging-observability: every field here is the agent CLI's own text, so an
    //     agent that writes a newline and an ANSI escape into its summary would otherwise forge a
    //     second, attacker-authored line in the operator's log — one event must stay one line.
    def "FR6: an agent payload carrying newlines and ANSI escapes renders one inert line"() {
        given:
        def listener = new LoggingAgentProgressListener()
        def forged = "done\n2026-08-31 12:00:00 ERROR factory lost the task\u001B[31m red \u001B[0m\u2028tail"

        when:
        def events = capture {
            listener.onProgress(new AgentProgressEvent.RoundFinished('success', [:], forged))
        }

        then: 'one event, one line — no line break of any flavour survives into the message'
        events.size() == 1
        !events[0].formattedMessage.contains('\n')
        !events[0].formattedMessage.contains('\r')
        !events[0].formattedMessage.contains('\u2028')

        and: 'the escape sequence is gone, so the forged text cannot colour or move a terminal'
        !events[0].formattedMessage.contains('\u001B')

        and: 'and the payload is still legible — neutralized, not dropped'
        events[0].formattedMessage.contains('factory lost the task')
    }

    // FR6: the same choke point guards the identity fields, not only the free-text summary
    def "FR6: a forged model or tool name cannot break out of its line"() {
        given:
        def listener = new LoggingAgentProgressListener()

        when:
        def events = capture {
            listener.onProgress(new AgentProgressEvent.RoundStarted("opus\nINFO forged", "s\u001B[2J"))
            listener.onProgress(new AgentProgressEvent.ToolStarted("Bash\nWARN forged"))
        }

        then:
        events.size() == 2
        events.every {
            !it.formattedMessage.contains('\n') && !it.formattedMessage.contains('\u001B')
        }
    }
}
