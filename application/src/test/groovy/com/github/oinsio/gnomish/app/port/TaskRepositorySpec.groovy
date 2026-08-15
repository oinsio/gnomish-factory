package com.github.oinsio.gnomish.app.port

import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import spock.lang.Specification

/**
 * Interface-shape spec for {@link TaskRepository}: no adapter implements this port
 * yet (the git adapter is future work, sections 2-3 of add-git-workflow), so this
 * spec exercises a minimal in-memory fake to prove the three methods are callable
 * end-to-end with real domain value objects, and that the fake's own bookkeeping —
 * standing in for the adapter contract described in the port's javadoc — behaves as
 * documented (create records the base ref, appendDecision resets a stale outcome).
 *
 * <p>Implements FR1 of add-git-workflow.
 */
class TaskRepositorySpec extends Specification {

    /** A minimal in-memory {@link TaskRepository}, honoring the D9/FR5 reset contract. */
    static class FakeTaskRepository implements TaskRepository {
        final Map<String, String> baseRefs = [:]
        final Map<String, List<Decision>> decisions = [:].withDefault { [] }
        final Map<String, TaskOutcome> outcomes = [:]

        @Override
        void createTask(TaskContext context, String baseRef) {
            baseRefs[context.taskId()] = baseRef
            decisions[context.taskId()] = new ArrayList<>(context.decisions())
            outcomes.remove(context.taskId())
        }

        @Override
        void appendDecision(String taskId, Decision decision) {
            decisions[taskId] << decision
            // D9/FR5: appending a resume decision resets outcome to null.
            outcomes.remove(taskId)
        }

        @Override
        void recordOutcome(String taskId, TaskOutcome outcome) {
            outcomes[taskId] = outcome
        }
    }

    def "createTask records the task context and its base reference"() {
        given: 'a repository and a fresh task context'
        def repository = new FakeTaskRepository()
        def context = new TaskContext('TASK-1', 'Fix the widget', 'Body text', [])

        when: 'the task is created from a base ref'
        repository.createTask(context, 'abc123')

        then: 'the base ref is durably associated with the task'
        repository.baseRefs['TASK-1'] == 'abc123'
    }

    def "appendDecision accumulates decisions for a task"() {
        given: 'a repository with an existing task'
        def repository = new FakeTaskRepository()
        def context = new TaskContext('TASK-1', 'Fix the widget', 'Body text', [])
        repository.createTask(context, 'abc123')

        when: 'a resume decision is appended'
        def decision = new Decision('proceed with plan B', 'build', 'operator', null)
        repository.appendDecision('TASK-1', decision)

        then: 'the decision is retained for the task, in order'
        repository.decisions['TASK-1'] == [decision]
    }

    def "appendDecision resets a previously recorded outcome to null (D9, FR5)"() {
        given: 'a task escalated in a prior visit'
        def repository = new FakeTaskRepository()
        def context = new TaskContext('TASK-1', 'Fix the widget', 'Body text', [])
        repository.createTask(context, 'abc123')
        def state = TaskState.atStageStart('build')
        def escalation = new EscalationReport.DecisionNeeded('needs input', [])
        repository.recordOutcome('TASK-1', new TaskOutcome.Escalated(state, escalation))

        expect: 'the outcome is recorded before resume'
        repository.outcomes['TASK-1'] != null

        when: 'the human decision that resumes the task is appended'
        repository.appendDecision('TASK-1', new Decision('proceed', null, null, null))

        then: 'the outcome is reset to null for the new visit'
        !repository.outcomes.containsKey('TASK-1')
    }

    def "recordOutcome durably records the terminal outcome for a task"() {
        given: 'a repository with an existing task'
        def repository = new FakeTaskRepository()
        def context = new TaskContext('TASK-1', 'Fix the widget', 'Body text', [])
        repository.createTask(context, 'abc123')
        def state = TaskState.atStageStart('build')

        when: 'the task completes'
        def outcome = new TaskOutcome.Completed(state)
        repository.recordOutcome('TASK-1', outcome)

        then: 'the outcome is retained by value'
        repository.outcomes['TASK-1'] == outcome
    }
}
