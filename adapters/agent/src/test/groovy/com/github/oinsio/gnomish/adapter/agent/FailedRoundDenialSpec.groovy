package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport

/**
 * FR3, D1 of fix-denial-report-attachment — the failure half of the denial read:
 * a round that dies before its close (a {@code roundTimeout} kill, a missing
 * result event) has no attempt record to carry denials on, but it must still
 * drain them from the environment. The guard's per-round delta cursor advances
 * only on a read, and an in-process escalation resume reuses the very same lease
 * and environment, so an undrained failed round hands its denials to the next
 * round's attempt — the hung round's blocked exfiltration reported as the next
 * attempt's.
 */
class FailedRoundDenialSpec extends AbstractDenialRoundSpec {

    static final def HUNG_ROUND_DENIAL = new Finding(
    'egress denied: paste.example.com:443', 'paste.example.com:443/upload', 'kind=http method=POST')

    static final def NEXT_ROUND_DENIAL = new Finding(
    'egress denied: pastebin.example.org:443', 'pastebin.example.org:443/api', 'kind=http method=POST')

    // FR3, D1: the drain is what advances the guard's delta cursor past the failed round
    def "a timed-out round drains its environment's denials"() {
        given:
        def source = scriptedSource([[HUNG_ROUND_DENIAL]])
        def logs = LogCaptureSupport.attach(ExecutorRoundExecution)

        when:
        executorFor('hangs-forever', source).execute(requestFor([roundTimeout: 1]))

        then:
        thrown(RoundTimeoutException)

        and: 'the hung round asked its environment for denials exactly once'
        source.reads() == 1

        and: 'FR15 of harden-logging-observability: denials attached to no attempt are named, with their count'
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.ROUND_DENIALS_ORPHANED_ON_FAILURE.head())
        }
        warned != null
        warned.level == Level.WARN
        warned.formattedMessage.contains('1')

        cleanup:
        logs.detach()
    }

    // FR3, D1: a stream with no result event dies before the close too — same drain
    def "a round with no result event drains its environment's denials"() {
        given:
        def source = scriptedSource([[HUNG_ROUND_DENIAL]])

        when:
        executorFor('missing-result-event', source).execute(requestFor())

        then:
        thrown(MissingResultEventException)

        and:
        source.reads() == 1
    }

    // FR3, D1, UX2: the round after a failed one reports its own denials, never the failed
    // round's — the misattribution an undrained round causes on an in-process resume
    def "the round after a failed round carries only its own denials"() {
        given: 'a guard whose next delta read answers the second round\'s denial'
        def source = scriptedSource([
            [HUNG_ROUND_DENIAL],
            [NEXT_ROUND_DENIAL]
        ])

        when: 'the first round hangs and is killed'
        executorFor('hangs-forever', source).execute(requestFor([roundTimeout: 1]))

        then:
        thrown(RoundTimeoutException)

        when: 'the same environment runs the next round to completion'
        def result = executorFor('plain-round', source).execute(requestFor())

        then: 'only its own denial lands on the result'
        result instanceof ExecutionResult.Completed
        result.denials() == [NEXT_ROUND_DENIAL]
    }

    // NFR-R1: the drain is best-effort — a read that throws must not mask the round's own
    // infrastructure failure, which is what the engine escalates on
    def "a throwing denial read does not mask the round's failure"() {
        given: 'an environment that cannot serve a denial read at all'
        def source = scriptedSource([null])
        def logs = LogCaptureSupport.attach(ExecutorRoundExecution)

        when:
        executorFor('hangs-forever', source).execute(requestFor([roundTimeout: 1]))

        then:
        thrown(RoundTimeoutException)

        and: 'FR15 of harden-logging-observability: the undrained cursor is a coded WARN of its own'
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.ROUND_DENIALS_UNREADABLE_ON_FAILURE.head())
        }
        warned != null
        warned.level == Level.WARN

        cleanup:
        logs.detach()
    }

    private ScriptedDenialRounds scriptedSource(List<List<Finding>> answers) {
        new ScriptedDenialRounds(hostSource(), answers)
    }
}
