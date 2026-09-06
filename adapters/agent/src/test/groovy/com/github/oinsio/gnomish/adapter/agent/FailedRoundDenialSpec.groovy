package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure
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
 *
 * <p>FR1 of fix-denial-attribution-durability: the drain no longer ends in the log. Each
 * failure leaves this adapter wrapped in an {@code ExecutorFailure} carrying what was drained,
 * with the original infrastructure failure as its cause, so the engine can copy the denials
 * onto the {@code CannotExecute} escalation the round left no attempt record to hold.
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
        def logs = LogCaptureSupport.attach(RoundDenialRead)

        when:
        executorFor('hangs-forever', source).execute(requestFor([roundTimeout: 1]))

        then: 'FR1: the timeout leaves the adapter wrapped, carrying the drained denial'
        def failure = thrown(ExecutorFailure)
        failure.cause() instanceof RoundTimeoutException
        failure.denials() == [
            Denial.unidentified(HUNG_ROUND_DENIAL)
        ]

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

        then: 'FR1: same wrapper, this time over the missing result event'
        def failure = thrown(ExecutorFailure)
        failure.cause() instanceof MissingResultEventException
        failure.denials() == [
            Denial.unidentified(HUNG_ROUND_DENIAL)
        ]

        and:
        source.reads() == 1
    }

    // FR1: "a process that would not start" is the third failure ExecutorFailure names
    //      (ExecutorFailure.java:11) — the launch is inside the wrapped region like the rest
    def "a round whose process will not start drains its environment's denials"() {
        given:
        def source = new ScriptedDenialRounds(hostSource(), [[HUNG_ROUND_DENIAL]], true)

        when:
        executorFor('plain-round', source).execute(requestFor())

        then: 'FR1: the start failure leaves the adapter wrapped, carrying what the box had recorded'
        def failure = thrown(ExecutorFailure)
        failure.cause() instanceof IllegalStateException
        failure.denials() == [
            Denial.unidentified(HUNG_ROUND_DENIAL)
        ]

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
        thrown(ExecutorFailure)

        when: 'the same environment runs the next round to completion'
        def result = executorFor('plain-round', source).execute(requestFor())

        then: 'only its own denial lands on the result'
        result instanceof ExecutionResult.Completed
        result.denials() == [
            Denial.unidentified(NEXT_ROUND_DENIAL)
        ]
    }

    // FR6 of harden-logging-observability: the denied destination is the guard's record of what
    //      the gnome asked for, so the orphan WARN renders it through LogText — a raw toString()
    //      of the list would let a chosen hostname forge a log record of its own
    def "the orphaned-denial warning renders its destinations through the sanitizer"() {
        given: 'a denial whose destination carries an escape sequence and a forged record'
        def forged = new Finding(
                'egress denied: \u001b[2Jevil.example.com\nJan 01 00:00 INFO all clear',
                'evil.example.com:443/x', 'kind=connect')
        def source = scriptedSource([[forged, NEXT_ROUND_DENIAL]])
        def logs = LogCaptureSupport.attach(RoundDenialRead)

        when:
        executorFor('hangs-forever', source).execute(requestFor([roundTimeout: 1]))

        then:
        thrown(ExecutorFailure)

        and: 'the line counts the denials and names every destination'
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.ROUND_DENIALS_ORPHANED_ON_FAILURE.head())
        }
        warned.formattedMessage.contains('2 egress denial')
        warned.formattedMessage.contains('evil.example.com')
        warned.formattedMessage.contains(NEXT_ROUND_DENIAL.message())

        and: 'FR6: one event stays one line, with no escape sequence left in the record'
        !warned.formattedMessage.contains('\n')
        !warned.formattedMessage.contains('\u001b')

        cleanup:
        logs.detach()
    }

    // NFR-R1: the drain is best-effort — a read that throws must not mask the round's own
    // infrastructure failure, which is what the engine escalates on
    def "a throwing denial read does not mask the round's failure"() {
        given: 'an environment that cannot serve a denial read at all'
        def source = scriptedSource([null])
        def logs = LogCaptureSupport.attach(RoundDenialRead)

        when:
        executorFor('hangs-forever', source).execute(requestFor([roundTimeout: 1]))

        then: 'NFR-R1: the round\'s own failure still comes out, with no denials to report'
        def failure = thrown(ExecutorFailure)
        failure.cause() instanceof RoundTimeoutException
        failure.denials().isEmpty()

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
