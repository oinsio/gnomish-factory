package com.github.oinsio.gnomish.app.port

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.time.Instant
import spock.lang.Specification

/**
 * Interface-shape spec for {@link TaskRepository}: no adapter implements this port
 * yet (the git adapter is future work, sections 2-3 of add-git-workflow), so this
 * spec exercises a minimal in-memory fake to prove the three methods are callable
 * end-to-end with real domain value objects, and that the fake's own bookkeeping —
 * standing in for the adapter contract described in the port's javadoc — behaves as
 * documented (create records the base ref, appendDecision resets a stale outcome).
 *
 * <p>Implements FR1 of add-git-workflow; FR3 of harden-task-branch-contract.
 */
class TaskRepositorySpec extends Specification {

    /** A minimal in-memory {@link TaskRepository}, honoring the D9/FR5 reset contract. */
    static class FakeTaskRepository implements TaskRepository {
        final Map<String, String> baseRefs = [:]
        final Map<String, List<Decision>> decisions = [:].withDefault { [] }
        final Map<String, TaskOutcome> outcomes = [:]
        final Map<String, TaskState> initialStates = [:]
        final Map<String, TaskState> resetStates = [:]

        @Override
        void createTask(TaskContext context, String baseRef, TaskState initialState) {
            baseRefs[context.taskId()] = baseRef
            decisions[context.taskId()] = new ArrayList<>(context.decisions())
            initialStates[context.taskId()] = initialState
            outcomes.remove(context.taskId())
        }

        @Override
        void appendDecision(String taskId, Decision decision, TaskState resetState) {
            decisions[taskId] << decision
            // D9/FR5: appending a resume decision resets outcome to null.
            outcomes.remove(taskId)
            // FR4 of harden-task-branch-contract: the attempt-counter reset rides the same write.
            resetStates[taskId] = resetState
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
        repository.createTask(context, 'abc123', TaskState.atStageStart('build'))

        then: 'the base ref is durably associated with the task'
        repository.baseRefs['TASK-1'] == 'abc123'
    }

    // FR3 of harden-task-branch-contract: the initial state is part of the SAME durable write as
    // the context — an implementation may not record the context alone and synthesize state later,
    // because a first-round crash would then leave a branch no resume can read.
    def "createTask records the initial state alongside the task context (FR3)"() {
        given: 'a repository and a fresh task context'
        def repository = new FakeTaskRepository()
        def context = new TaskContext('TASK-1', 'Fix the widget', 'Body text', [])
        def initialState = TaskState.atStageStart('build')

        when: 'the task is created'
        repository.createTask(context, 'abc123', initialState)

        then: 'the starting position is recorded with it'
        repository.initialStates['TASK-1'] == initialState
    }

    def "appendDecision accumulates decisions for a task"() {
        given: 'a repository with an existing task'
        def repository = new FakeTaskRepository()
        def context = new TaskContext('TASK-1', 'Fix the widget', 'Body text', [])
        repository.createTask(context, 'abc123', TaskState.atStageStart('build'))

        when: 'a resume decision is appended'
        def decision = new Decision('proceed with plan B', 'build', 'operator', null)
        repository.appendDecision('TASK-1', decision, TaskState.atStageStart('build'))

        then: 'the decision is retained for the task, in order'
        repository.decisions['TASK-1'] == [decision]
    }

    def "appendDecision resets a previously recorded outcome to null (D9, FR5)"() {
        given: 'a task escalated in a prior visit'
        def repository = new FakeTaskRepository()
        def context = new TaskContext('TASK-1', 'Fix the widget', 'Body text', [])
        repository.createTask(context, 'abc123', TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        def escalation = new EscalationReport.DecisionNeeded('needs input', [])
        repository.recordOutcome('TASK-1', new TaskOutcome.Escalated(state, escalation))

        expect: 'the outcome is recorded before resume'
        repository.outcomes['TASK-1'] != null

        when: 'the human decision that resumes the task is appended'
        repository.appendDecision('TASK-1', new Decision('proceed', null, null, null), TaskState.atStageStart('build'))

        then: 'the outcome is reset to null for the new visit'
        !repository.outcomes.containsKey('TASK-1')
    }

    // FR4 of harden-task-branch-contract: the decision and the attempt-counter reset it implies
    // are true only together, so the port takes them together — a caller cannot record one and
    // leave the other for a later write, which is the "answered, but still exhausted" kill window.
    def "appendDecision records the attempt-counter reset with the decision (FR4)"() {
        given: 'a task whose stage burned its attempts before parking'
        def repository = new FakeTaskRepository()
        def context = new TaskContext('TASK-1', 'Fix the widget', 'Body text', [])
        repository.createTask(context, 'abc123', TaskState.atStageStart('build'))
        def exhausted = TaskState.atStageStart('build').recordQualityFailure(new AttemptRecord(
                        0, AttemptRecord.Result.QUALITY_FAILURE, Instant.EPOCH, [],
                        ExecutorUsage.none(), JudgeUsage.none(), []))

        when: 'the human answer is appended'
        repository.appendDecision('TASK-1', new Decision('proceed', 'build', 'operator', null),
                exhausted.resetAttempts())

        then: 'the reset landed with it — same position, nothing burned'
        repository.resetStates['TASK-1'].attemptsUsed() == 0
        repository.resetStates['TASK-1'].position() == exhausted.position()
    }

    def "recordOutcome durably records the terminal outcome for a task"() {
        given: 'a repository with an existing task'
        def repository = new FakeTaskRepository()
        def context = new TaskContext('TASK-1', 'Fix the widget', 'Body text', [])
        repository.createTask(context, 'abc123', TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')

        when: 'the task completes'
        def outcome = new TaskOutcome.Completed(state)
        repository.recordOutcome('TASK-1', outcome)

        then: 'the outcome is retained by value'
        repository.outcomes['TASK-1'] == outcome
    }
}
