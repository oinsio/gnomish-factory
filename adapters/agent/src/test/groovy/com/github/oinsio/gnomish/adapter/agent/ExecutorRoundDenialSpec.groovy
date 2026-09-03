package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport

/**
 * FR3, D1 of fix-denial-report-attachment: the round reads its environment's
 * denials at round close and carries them out on the {@link ExecutionResult},
 * the same channel usage and trace already ride. Both shapes of executed round
 * carry them — a decision round had a live round too, so it can have denied just
 * as much as a completed one.
 *
 * <p>Driven against the real host round through the fake agent binary, with only
 * the environment substituted, so the wiring under test is the production path
 * rather than a hand-built stand-in for it.
 */
class ExecutorRoundDenialSpec extends AbstractDenialRoundSpec {

    static final def DENIAL = new Finding(
    'egress denied: paste.example.com:443', 'paste.example.com:443/upload', 'kind=http method=POST')

    private ExecutionResult runWith(String scenario, List<Finding> denials) {
        def source = new ScriptedDenialRounds(hostSource(), [denials])
        executorFor(scenario, source).execute(requestFor())
    }

    // FR3: a completed round whose environment reports denials carries them out
    def "FR3: a completed round carries its environment's denials"() {
        when:
        def result = runWith('plain-round', [DENIAL])

        then:
        result instanceof ExecutionResult.Completed
        result.denials() == [DENIAL]
    }

    // FR3, D1: a DecisionNeeded round had a live round too — its denials must not be dropped
    def "FR3: a decision round carries its environment's denials"() {
        when:
        def result = runWith('decision-needed', [DENIAL])

        then:
        result instanceof ExecutionResult.DecisionNeeded
        result.denials() == [DENIAL]
    }

    // NFR-R1: the denial read is observability of work the gnome already finished — a source that
    //     cannot answer at all must cost the round nothing. The port contract promises a degraded
    //     empty answer, but a round is far too expensive to stake on a collaborator keeping it.
    def "NFR-R1: a throwing denial read still yields a completed round"() {
        given:
        def logs = LogCaptureSupport.attach(ExecutorRoundExecution)

        when: 'the round finishes and its environment cannot serve the denial read at all'
        def result = runWith('plain-round', null)

        then: 'the finished round survives, reporting no denials rather than being discarded'
        noExceptionThrown()
        result instanceof ExecutionResult.Completed
        result.denials() == []

        and: 'FR15 of harden-logging-observability: an empty denial list that means "could not read" says so'
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.ROUND_DENIALS_UNREADABLE_ON_FINISH.head())
        }
        warned != null
        warned.level == Level.WARN

        cleanup:
        logs.detach()
    }

    // FR1, UX2: an environment that answers empty — a quiet round, or one with no guard at all —
    //     lands as an empty list on the result, not as an absent or null one. That a guard-less
    //     environment really answers empty is the port default's own contract spec
    //     (TaskExecutionEnvironmentContract); this pins what the round does with that answer.
    def "an environment reporting no denials yields an empty denial list"() {
        when:
        def result = runWith('plain-round', [])

        then:
        result instanceof ExecutionResult.Completed
        result.denials() == []
    }
}
