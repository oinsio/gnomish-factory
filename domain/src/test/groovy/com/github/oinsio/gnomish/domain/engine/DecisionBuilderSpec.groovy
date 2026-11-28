package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * The one builder of a decision (design D4 as amended 2026-11-21, D8 row "The question a decision
 * answers" of extract-agent-round-runner): the stage comes from the position, the question and its
 * options from the consumed outcome's report — only when that report is a decision request — and
 * the reply, the author and the instant are the caller's.
 *
 * <p>FR9, NFR-R2 of extract-agent-round-runner.
 */
class DecisionBuilderSpec extends Specification {

    private static final Instant AT = Instant.parse('2026-11-21T10:00:00Z')

    private static final EscalationReport.DecisionNeeded ASKED = new EscalationReport.DecisionNeeded(
    UntrustedText.agent('Refactor or patch?'),
    [
        UntrustedText.agent('refactor'),
        UntrustedText.agent('patch')
    ])

    // FR9: the stage is the one the position names — at a gate the stage that passed, none past the end
    def "a decision at #position is scoped to stage #stage"() {
        when:
        def decision = DecisionBuilder.answering(position, null, 'go on', 'tracker', AT)

        then:
        decision.stage() == stage
        decision.body() == 'go on'
        decision.author() == 'tracker'
        decision.time() == AT

        where:
        position || stage
        new Position.AtStage('build') || 'build'
        new Position.AwaitingApproval('review') || 'review'
        new Position.PipelineEnd() || null
    }

    // FR9: the reply to a decision request records the question and options it answers
    def "a reply to a decision request records the question and options of the consumed report"() {
        when:
        def decision = DecisionBuilder.answering(new Position.AtStage('build'), ASKED, 'refactor', 'operator', AT)

        then:
        decision.answers() == ASKED
        decision.answers().question().forLog() == 'Refactor or patch?'
        decision.answers().options()*.forLog() == ['refactor', 'patch']
        decision.body() == 'refactor'
        decision.author() == 'operator'
    }

    // FR9: a reply to any other escalation, or to no recorded outcome, records no question
    def "a reply consuming #report records no question"() {
        expect:
        DecisionBuilder.answering(new Position.AtStage('build'), report, 'retry', 'tracker', AT).answers() == null

        where:
        report << [
            new EscalationReport.AttemptsExhausted(3),
            null
        ]
    }
}
