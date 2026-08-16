package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.time.Instant
import spock.lang.Specification

/**
 * Activity and Outcome: the richer live-activity shape (executing/verifying/
 * awaitingInput, every variant carrying a since instant) and the report-model
 * mirror of TaskOutcome's four sealed variants, both required by the status-report
 * JSON contract (design D7). Implements FR11, D7 of add-manual-run.
 */
class ActivityOutcomeSpec extends Specification {

    private static final Instant SINCE = Instant.parse('2026-07-16T14:41:02Z')

    // FR11: Activity.Executing carries a since instant; the single-arg constructor
    // defaults currentTool/toolCalls for callers with no live tool detail yet
    def "Executing carries the since instant, defaulting currentTool and toolCalls when unspecified"() {
        given:
        def activity = new Activity.Executing(SINCE)

        expect:
        activity.since() == SINCE
        activity.currentTool() == null
        activity.toolCalls() == 0
    }

    // FR7, UX1, D10, D12 of add-agent-executor: Executing can carry live tool detail
    def "Executing carries currentTool and toolCalls when specified"() {
        given:
        def activity = new Activity.Executing(SINCE, 'run_tests', 2)

        expect:
        activity.since() == SINCE
        activity.currentTool() == 'run_tests'
        activity.toolCalls() == 2
    }

    // FR11: Activity.Verifying carries a checkRef and a since instant
    def "Verifying carries the checkRef and since instant"() {
        given:
        def checkRef = new CheckRef(0, 'command:./gradlew test')

        when:
        def activity = new Activity.Verifying(checkRef, SINCE)

        then:
        activity.checkRef() == checkRef
        activity.since() == SINCE
    }

    // FR11: Activity.AwaitingInput carries a prompt and a since instant
    def "AwaitingInput carries the prompt text and since instant"() {
        when:
        def activity = new Activity.AwaitingInput('Refactor or patch? ', SINCE)

        then:
        activity.prompt() == 'Refactor or patch? '
        activity.since() == SINCE
    }

    // FR11, D7: Outcome.from maps every TaskOutcome variant to its report-model equivalent
    def "Outcome.from maps TaskOutcome.Completed to Outcome.Completed"() {
        given:
        def outcome = new TaskOutcome.Completed(TaskState.atStageStart('implement'))

        expect:
        Outcome.from(outcome) == new Outcome.Completed()
    }

    def "Outcome.from maps TaskOutcome.Paused to Outcome.Paused naming the passed stage"() {
        given:
        def outcome = new TaskOutcome.Paused(TaskState.atStageStart('review'), 'implement')

        expect:
        Outcome.from(outcome) == new Outcome.Paused('implement')
    }

    def "Outcome.from maps TaskOutcome.Escalated to Outcome.Escalated carrying the report"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)
        def outcome = new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report)

        expect:
        Outcome.from(outcome) == new Outcome.Escalated(report)
    }

    def "Outcome.from maps TaskOutcome.Aborted to Outcome.Aborted carrying failedAt and cause"() {
        given:
        def failedAt = new AttemptKey('t1', 'implement', 0)
        def outcome = new TaskOutcome.Aborted(TaskState.atStageStart('implement'), failedAt, 'disk full')

        expect:
        Outcome.from(outcome) == new Outcome.Aborted(failedAt, 'disk full')
    }

    // D7: LiveActivity.idle produces null activity, null escalation and null outcome
    def "LiveActivity.idle carries no activity, no escalation, no outcome"() {
        expect:
        LiveActivity.idle() == new LiveActivity(null, null, null)
    }
}
