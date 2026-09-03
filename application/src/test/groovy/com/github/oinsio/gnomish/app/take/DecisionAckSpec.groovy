package com.github.oinsio.gnomish.app.take

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Instant
import spock.lang.Specification

/**
 * FR12 of harden-task-branch-contract: a human decision is durable on the task branch before its
 * acknowledge posts, and an intent whose acknowledge never landed is re-driven — after the tracker
 * is asked whether it landed after all.
 */
class DecisionAckSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-1')

    private static TaskContext contextWith(String... decisions) {
        new TaskContext('PROJ-1', 'title', 'body',
                decisions.collect {
                    new Decision(it, 'build', 'tracker', Instant.parse('2026-07-18T09:00:00Z'))
                })
    }

    Tracker tracker = Mock()

    // FR12: acknowledging first would consume the reply — the next collection starts after the ack —
    // while the branch still carried no answer, so the commit comes first.
    def "the decision is committed to the branch before the acknowledge posts"() {
        given:
        def decided = contextWith('go ahead')
        def steps = []
        tracker.acknowledgeDecision(REF, 'go ahead') >> {
            steps << 'acknowledge'
        }

        when:
        def result = DecisionAck.appendThenAcknowledge(tracker, REF, 'go ahead', {
            steps << 'commit'
            decided
        })

        then: 'the branch commit is durable first, and no probe is spent on a fresh decision'
        steps == ['commit', 'acknowledge']
        0 * tracker.collectDecisions(*_)
        result == decided
    }

    // FR10, FR12: recovery verifies the effect at the target — a reply no longer pending means the
    // acknowledge landed and nothing is written again.
    def "a re-drive whose acknowledge already landed posts nothing"() {
        given:
        tracker.collectDecisions(REF) >> []

        when:
        DecisionAck.redriveAcknowledge(tracker, REF, contextWith('go ahead'), 'go ahead')

        then:
        0 * tracker.acknowledgeDecision(*_)
    }

    // FR12: the kill window between the decision commit and its acknowledge — the reply is still
    // pending, so the acknowledge is re-driven (upsert, no duplicate).
    def "a re-drive whose acknowledge never landed posts it once"() {
        given:
        tracker.collectDecisions(REF) >> [
            new HumanReply('go ahead', Instant.parse('2026-07-18T10:00:00Z'))
        ]

        when:
        DecisionAck.redriveAcknowledge(tracker, REF, contextWith('go ahead'), 'go ahead')

        then:
        1 * tracker.acknowledgeDecision(REF, 'go ahead')
    }

    // FR12: an unaskable tracker reads as "not there" — a redundant upsert beats a lost transition.
    def "an unaskable tracker re-drives rather than skipping"() {
        given:
        tracker.collectDecisions(REF) >> {
            throw new RuntimeException('tracker unreachable')
        }

        and:
        def logs = LogCaptureSupport.attach(DecisionAck)

        when:
        DecisionAck.redriveAcknowledge(tracker, REF, contextWith('go ahead'), 'go ahead')

        then:
        1 * tracker.acknowledgeDecision(REF, 'go ahead')

        and: 'FR15 of harden-logging-observability: the unverifiable probe is a coded WARN naming the task'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.DECISION_ACK_UNVERIFIED.head())
        }
        event != null
        event.level == Level.WARN
        event.formattedMessage.contains(REF.id())

        cleanup:
        logs.detach()
    }

    // FR12: what marks an owed acknowledge — a pending reply the branch has already recorded.
    def "#label"() {
        expect:
        DecisionAck.unacknowledged(replies, context) == owed

        where:
        label | replies | context || owed
        'a pending reply the branch already recorded is owed an acknowledge' |
                [
                    new HumanReply('go ahead', Instant.parse('2026-07-18T10:00:00Z'))
                ] | contextWith('go ahead') ||
                'go ahead'
        'no pending reply means nothing is owed' | [] | contextWith('go ahead') || null
        'a branch with no decisions has nothing to acknowledge' |
                [
                    new HumanReply('go ahead', Instant.parse('2026-07-18T10:00:00Z'))
                ] | contextWith() || null
        'a pending reply the branch never recorded is a fresh answer, not an owed acknowledge' |
                [
                    new HumanReply('a new answer', Instant.parse('2026-07-18T10:00:00Z'))
                ] | contextWith('go ahead') ||
                null
    }
}
