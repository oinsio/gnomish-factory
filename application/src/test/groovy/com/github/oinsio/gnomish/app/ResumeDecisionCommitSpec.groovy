package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * {@link ResumeDecisionCommit}: the tracker reply both {@code take} media fold into a decision is
 * stamped with the park's stage — the stage the recorded position names — and the author
 * {@code tracker}, and is appended to a copy of the context.
 *
 * <p>D12 of add-tracker-port; FR1 of make-checkpoint-gate-durable; FR18 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
class ResumeDecisionCommitSpec extends Specification {

    static final Instant AT = Instant.parse('2026-10-09T12:00:00Z')

    static final EscalationReport.DecisionNeeded ASKED = new EscalationReport.DecisionNeeded(
    UntrustedText.branchDocument('Refactor or patch?'),
    [
        UntrustedText.branchDocument('refactor'),
        UntrustedText.branchDocument('patch')
    ])

    // FR1 of make-checkpoint-gate-durable: at a gate the decision belongs to the stage that passed;
    //     past the pipeline's end to no stage
    def "a reply at #position is stamped with stage #stage and the tracker author"() {
        when:
        def decision = ResumeDecisionCommit.decisionFor(new TaskState(position, 0, [], UsageSnapshot.EMPTY, ExecutorVersions.none()), null, 'go on', AT)

        then:
        decision.body() == 'go on'
        decision.stage() == stage
        decision.author() == 'tracker'
        decision.time() == AT // FR18 of supervise-daemon-loops-and-embed-dashboard: the caller's instant

        where:
        position | stage
        new Position.AtStage('build') | 'build'
        new Position.AwaitingApproval('release') | 'release'
        new Position.PipelineEnd() | null
    }

    // FR9 of extract-agent-round-runner: the question comes from the consumed outcome's report —
    //     a decision request only
    def "a reply consuming #consumed records question #question"() {
        when:
        def decision = ResumeDecisionCommit.decisionFor(TaskState.atStageStart('build'), consumed, 'refactor', AT)

        then:
        decision.answers()?.question()?.forLog() == question
        decision.answers()?.options()*.forLog() == options

        where:
        consumed || question | options
        new RecordedOutcome.Escalated(ASKED) || 'Refactor or patch?' | ['refactor', 'patch']
        new RecordedOutcome.Escalated(new EscalationReport.AttemptsExhausted(1)) || null | null
        new RecordedOutcome.Paused('build') || null | null
        null || null | null
    }

    def "appendTo returns a copy of the context with the decision last"() {
        given:
        def context = new TaskContext('PROJ-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), [])
        def decision = ResumeDecisionCommit.decisionFor(TaskState.atStageStart('build'), null, 'go on', AT)

        when:
        def appended = ResumeDecisionCommit.appendTo(context, decision)

        then:
        appended.decisions() == [decision]
        appended.taskId() == 'PROJ-1'
        context.decisions().isEmpty()
    }
}
