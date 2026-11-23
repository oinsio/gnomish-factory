package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport

/**
 * FR3, D1 of fix-denial-report-attachment — the failure half of the denial read:
 * a round that fails (a {@code roundTimeout} kill, a missing result event, a CLI
 * that will not start) or is discarded before its close (a dead guard) has no attempt
 * record to carry denials on, but it must still drain them from the environment;
 * since task 5.1 of extract-agent-round-runner the agent round runs through the shared
 * round wrapper, which reads them at the close of a failed round and at the discard of
 * a dead guard's, and only the discard names them as orphaned. The guard's per-round delta cursor advances
 * only on a read, and an in-process escalation resume reuses the very same lease
 * and environment, so an undrained failed round hands its denials to the next
 * round's attempt — the hung round's blocked exfiltration reported as the next
 * attempt's.
 *
 * <p>FR1 of fix-denial-attribution-durability: the drain no longer ends in the log. Each
 * failure leaves this adapter as its {@code failed/infrastructure} result carrying what was
 * drained (FR7 of define-executor-contract), so the engine's classifier copies the denials onto
 * the {@code CannotExecute} escalation the round left no attempt record to hold.
 */
class FailedRoundDenialSpec extends AbstractDenialRoundSpec {

    static final def HUNG_ROUND_DENIAL = new Finding(
    'egress denied: paste.example.com:443', 'paste.example.com:443/upload', 'kind=http method=POST')

    static final def NEXT_ROUND_DENIAL = new Finding(
    'egress denied: pastebin.example.org:443', 'pastebin.example.org:443/api', 'kind=http method=POST')

    // FR3, D1: the drain is what advances the guard's delta cursor past the failed round
    // Scenario: pipeline/stage-engine / A round killed before its close reports its denials
    // Scenario: sandbox/sandbox-egress / Denied exfiltration attempt of a hung round reaches the report
    def "a timed-out round drains its environment's denials"() {
        given:
        def source = scriptedSource([[HUNG_ROUND_DENIAL]])
        def logs = LogCaptureSupport.attach(RoundDenialRead)

        when:
        def result = executorFor('hangs-forever', source).execute(requestFor([roundTimeout: 1]))

        then: 'FR1: the timeout leaves the adapter as its failed result, carrying the drained denial'
        InfrastructureResults.endedSaying(result, 'the overall bound')
        result.denials() == [
            Denial.unidentified(HUNG_ROUND_DENIAL)
        ]

        and: 'the hung round asked its environment for denials exactly once'
        source.reads() == 1

        and: 'the timed-out round reached the shared wrapper as its result, so its denials ride that result and no orphan line is owed'
        logs.list.every {
            !it.formattedMessage.startsWith(OperatorEvent.ROUND_DENIALS_ORPHANED_ON_FAILURE.head())
        }

        cleanup:
        logs.detach()
    }

    // FR3, D1: a stream with no result event dies before the close too — same drain
    def "a round with no result event drains its environment's denials"() {
        given:
        def source = scriptedSource([[HUNG_ROUND_DENIAL]])

        when:
        def result = executorFor('missing-result-event', source).execute(requestFor())

        then: 'FR1: same result, this time over the missing result event'
        InfrastructureResults.endedSaying(result, 'no result event')
        result.denials() == [
            Denial.unidentified(HUNG_ROUND_DENIAL)
        ]

        and:
        source.reads() == 1
    }

    // FR1: "a process that would not start" is the third failure the round reports with its
    //      denials — the launch is inside the guarded region like the rest
    def "a round whose process will not start drains its environment's denials"() {
        given:
        def source = new ScriptedDenialRounds(hostSource(), [[HUNG_ROUND_DENIAL]], true)

        when:
        def result = executorFor('plain-round', source).execute(requestFor())

        then: 'FR1: the start failure leaves the adapter as its failed result, carrying what the box had recorded'
        InfrastructureResults.endedSaying(result, 'refused to start the agent CLI')
        result.denials() == [
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
        def killed = executorFor('hangs-forever', source).execute(requestFor([roundTimeout: 1]))

        then:
        InfrastructureResults.endedSaying(killed, 'the overall bound')

        when: 'the same environment runs the next round to completion'
        def result = executorFor('plain-round', source).execute(requestFor())

        then: 'only its own denial lands on the result'
        (result.status() instanceof ExecutionResult.Completed && result.answer() == null)
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
        and: 'a guard that dies while the round runs, so the round is discarded before its close'
        def source = scriptedSource([[forged, NEXT_ROUND_DENIAL]])
        source.guardDiesAtProbe = 1
        def logs = LogCaptureSupport.attach(RoundDenialRead)

        when:
        def result = executorFor('plain-round', source).execute(requestFor())

        then:
        InfrastructureResults.endedSaying(result, 'egress guard')

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
        source.guardDiesAtProbe = 1
        def logs = LogCaptureSupport.attach(RoundDenialRead)

        when: 'its guard dies while the round runs, so the round is discarded before its close'
        def result = executorFor('plain-round', source).execute(requestFor())

        then: 'NFR-R1: the round\'s own failure still comes out, with no denials to report'
        InfrastructureResults.endedSaying(result, 'egress guard')
        result.denials().isEmpty()

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
