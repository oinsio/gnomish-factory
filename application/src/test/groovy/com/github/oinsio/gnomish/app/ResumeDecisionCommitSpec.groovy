package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * {@link ResumeDecisionCommit}: the tracker reply both {@code take} media fold into a decision is
 * stamped with the park's stage — the stage the recorded position names — and the author
 * {@code tracker}, and is appended to a copy of the context.
 *
 * <p>D12 of add-tracker-port; FR1 of make-checkpoint-gate-durable.
 */
class ResumeDecisionCommitSpec extends Specification {

    // FR1 of make-checkpoint-gate-durable: at a gate the decision belongs to the stage that passed;
    //     past the pipeline's end to no stage
    def "a reply at #position is stamped with stage #stage and the tracker author"() {
        when:
        def decision = ResumeDecisionCommit.decisionFor(new TaskState(position, 0, [], ExecutorUsage.none()), 'go on')

        then:
        decision.body() == 'go on'
        decision.stage() == stage
        decision.author() == 'tracker'
        decision.time() != null

        where:
        position | stage
        new Position.AtStage('build') | 'build'
        new Position.AwaitingApproval('release') | 'release'
        new Position.PipelineEnd() | null
    }

    def "appendTo returns a copy of the context with the decision last"() {
        given:
        def context = new TaskContext('PROJ-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), [])
        def decision = ResumeDecisionCommit.decisionFor(TaskState.atStageStart('build'), 'go on')

        when:
        def appended = ResumeDecisionCommit.appendTo(context, decision)

        then:
        appended.decisions() == [decision]
        appended.taskId() == 'PROJ-1'
        context.decisions().isEmpty()
    }
}
