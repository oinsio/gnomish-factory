package com.github.oinsio.gnomish.status

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import spock.lang.Specification

/**
 * The operator plane's anchor vocabulary (FR2) and the one canonical task-summary renderer (FR3)
 * of harden-logging-observability.
 *
 * <p>These are assertions about wording, not about plumbing: an anchor exists so an operator can
 * navigate a log file by it, so what the line says — and at which level it says it — is the whole
 * contract. The summary renderer is exercised across every outcome family precisely because the
 * design's promise is that one renderer serves all of them; a family rendered by a second code
 * path would show up here as a differing form.
 */
class AnchorLogSpec extends Specification {

    private LogCaptureSupport capture

    def setup() {
        capture = LogCaptureSupport.attach(AnchorLog)
    }

    def cleanup() {
        capture.detach()
    }

    // FR2: the claim anchor names the task and the occupancy the claim leaves behind
    def "the claim anchor names the task and states occupancy as a fraction"() {
        when:
        AnchorLog.claimAcquired('task-7', 1, 2)

        then:
        capture.list.size() == 1
        capture.list[0].level == Level.INFO
        capture.list[0].formattedMessage == 'claim acquired for task task-7: 1 of 2 slot(s) free'
    }

    // FR2: serve started names the configuration actually in effect
    def "the serve start anchor names every configured value"() {
        when:
        AnchorLog.serveStarted(new AnchorLog.ServeConfig(
                        'factory-a1b2', 2, 3, Duration.ofSeconds(30), Duration.ofSeconds(45)))

        then:
        capture.list.size() == 1
        capture.list[0].level == Level.INFO

        and: 'the whole effective configuration is on the one line'
        String message = capture.list[0].formattedMessage
        message.contains('instance=factory-a1b2')
        message.contains('slots=2')
        message.contains('wipLimit=3')
        message.contains('idlePoll=PT30S')
        message.contains('sigtermGrace=PT45S')
    }

    // FR2: the stopping anchor carries the wire-safe reason the snapshot also records
    def "the serve stopping anchor carries the reason"() {
        when:
        AnchorLog.serveStopping('signal')

        then:
        capture.list.size() == 1
        capture.list[0].level == Level.INFO
        capture.list[0].formattedMessage == 'serve stopping: reason=signal'
    }

    // FR3: one renderer, every outcome family — including the post-harden quarantine park
    def "the summary renders #outcome at #level with its stage, attempts and wall time"() {
        when:
        AnchorLog.taskSummary(new TaskSummary(outcome, parkReason, 'implement', 2, Duration.ofSeconds(75), [:]))

        then:
        capture.list.size() == 1
        capture.list[0].level == level

        and:
        String message = capture.list[0].formattedMessage
        message.startsWith("task summary: outcome=${outcome.word()}")
        message.contains('stage=implement')
        message.contains('attempts=2')
        message.contains('wall=PT1M15S')

        and: 'a park names its reason inline; every other outcome adds no parenthetical'
        message.contains(' (') == (parkReason != null)
        parkReason == null || message.contains("(${parkReason})")

        where:
        outcome | parkReason | level
        TaskSummary.Outcome.DELIVERED | null | Level.INFO
        TaskSummary.Outcome.AWAITING_HUMAN | 'ESCALATION' | Level.INFO
        TaskSummary.Outcome.AWAITING_HUMAN | 'INFRA' | Level.INFO
        TaskSummary.Outcome.ABORTED | null | Level.WARN
        TaskSummary.Outcome.REVOKED | null | Level.WARN
    }

    // FR3: the pipeline-end position is named, never rendered as a bare null
    def "a task that finished the pipeline renders its stage as pipelineEnd"() {
        when:
        AnchorLog.taskSummary(new TaskSummary(
                        TaskSummary.Outcome.DELIVERED, null, null, 1, Duration.ofSeconds(3), [:]))

        then:
        capture.list[0].formattedMessage.contains('stage=pipelineEnd')
    }

    // FR3: unreported tokens are stated as unreported — never fabricated as zeros
    def "token usage renders per model, and an empty map says unreported"() {
        when:
        AnchorLog.taskSummary(new TaskSummary(
                        TaskSummary.Outcome.DELIVERED, null, 'review', 1, Duration.ofSeconds(3),
                        ['sonnet': new TokenUsage(10, 20, 30, 40)]))
        AnchorLog.taskSummary(new TaskSummary(
                        TaskSummary.Outcome.DELIVERED, null, 'review', 1, Duration.ofSeconds(3), [:]))

        then:
        List<ILoggingEvent> events = capture.list
        events[0].formattedMessage.contains('tokens={sonnet=10/20/30/40}')
        events[1].formattedMessage.contains('tokens=unreported')
    }
}
