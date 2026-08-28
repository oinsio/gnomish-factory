package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState

/**
 * FR6, FR25, D19, UX2 of add-sandbox-core: {@link ContainerResumeRunner}'s escalated-outcome
 * continuation, daemon-free over {@link ContainerResumeSpecBase}'s scripted fixture — the same
 * dialog the host path uses, with the operator's decision committed factory-side over bare git
 * objects before any environment materializes, and the resumed drive reaching Completed.
 */
class ContainerResumeEscalationSpec extends ContainerResumeSpecBase {

    private void recordEscalated(String taskId) {
        repository.createTask(context(taskId), 'HEAD', TaskState.atStageStart('build'))
        repository.recordOutcome(taskId,
                new TaskOutcome.Escalated(pipelineEndState(), new EscalationReport.DecisionNeeded('how?', ['a'])))
        commitStateAtPipelineEnd(taskId)
    }

    // FR6, FR25, D19: an escalated task resumes through the dialog; the operator's decision is
    // committed factory-side over bare objects, and the continuation drives to Completed.
    def "resuming an escalated task appends the operator decision factory-side and completes"() {
        given: 'an escalated task parked at PipelineEnd, so the resumed drive completes at once'
        recordEscalated('T-ESC')

        when:
        resume('T-ESC', lines('fix applied'), sink())

        then: 'the branch carries the completed outcome below the cleanup tip, decision included'
        def taskJson = taskJsonBelowTip('T-ESC')
        taskJson.contains('"completed"')
        taskJson.contains('fix applied')
    }

    // FR6: a bare Enter resumes after an environment fix without appending any decision.
    def "resuming an escalated task with a blank answer appends no decision"() {
        given:
        recordEscalated('T-BLANK')

        when:
        resume('T-BLANK', lines(''), sink())

        then:
        def taskJson = taskJsonBelowTip('T-BLANK')
        taskJson.contains('"completed"')
        taskJson.contains('"decisions":[]')
    }

    // NFR-R1: an escalated outcome without a recorded lastEscalation is a broken invariant —
    // an internal error naming the task, never a dialog over a missing report.
    def "an escalated outcome without a recorded escalation is an internal error"() {
        given:
        repository.createTask(context('T-NOREP'), 'HEAD', TaskState.atStageStart('build'))
        commitTaskJson('T-NOREP',
                new TaskOutcome.Escalated(pipelineEndState(), new EscalationReport.DecisionNeeded('q', ['a'])),
                null)

        when:
        resume('T-NOREP', lines(''), sink())

        then:
        def e = thrown(InternalErrorException)
        e.message.contains('T-NOREP')
    }
}
