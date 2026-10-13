package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Identity spec of design D4, D7 and D10 of make-checkpoint-gate-durable (the {@code
 * TaskRepository.resumeFrom} and "three outcome-clearing writes" rows; {@code testing.md},
 * "Invariant specs across a flow"): {@code appendDecision}, {@code approveCheckpoint} and {@code
 * resumeFrom} are the only writers of a cleared outcome, and each lands the state the continuation
 * resumes into in the same commit — "outcome consumed" and "state reset/approved" are only true
 * together ({@code crash-consistency.md}, item 4). The consumed request leaves the tip in that same
 * commit (FR14, the hygiene of D10).
 *
 * <p>Run on both lifecycle media ({@link ContinuationMedium}): each park is a real run with the fake
 * agent — a manual pass, a spent attempt limit, a gnome that asks — and each continuation is the
 * real {@code run --resume} arm that production dispatches the park to. A request is planted on
 * every parked tip first ({@link BranchHistory#STALE_REQUEST}), so the removal is observed on every
 * row rather than only where the medium happens to carry one; on the container medium the asking
 * round's own request, under its token, is on the tip as well.
 *
 * <p>The expected state is spelled here from the parked state alone: the approval moves the
 * position past the gate and resets nothing; the resumed and the decided writes keep the position,
 * zero the attempts and keep the totals.
 *
 * <p>FR7, FR8, FR14, M1 of make-checkpoint-gate-durable.
 */
class ConsumedOutcomeIdentitySpec extends Specification implements BareGitRepoFixture {

    private static final String TASK = 'CONSUME-1'

    @TempDir
    Path tempDir

    // FR7, FR8, FR14, M1: outcome == null iff state.json carries the continuation's state, one
    //     commit after the park; no decisions/ entry after the continuation
    // Scenario: lifecycle/task-branch-contract / A consumed outcome does not survive its consumption
    // Scenario: lifecycle/task-branch-contract / One task is one ref, one transition one commit
    // Scenario: lifecycle/task-branch-contract / Only three writes clear the outcome
    // Scenario: lifecycle/task-branch-contract / No in-memory attempt reset precedes its write
    // Scenario: lifecycle/task-branch-contract / An escalating stop rides its round record
    def "FR8: #continuation on #medium clears the outcome with its own state in one commit and leaves no request"() {
        given: 'a parked task, a carried-over request on its tip, on origin'
        ContinuationMedium m = medium == 'host'
                ? new HostContinuationMedium(tempDir.resolve('host'))
                : new ContainerContinuationMedium(tempDir.resolve('container'))
        m.park(TASK, definition, scenario)
        m.plantStaleRequest(TASK)
        def history = new BranchHistory(m.origin(), TASK)
        String park = history.tip()
        TaskState parked = history.state(park)
        TaskState expected = continuationState(continuation, parked, definition)

        expect: 'the park recorded an outcome, the tip carries a request, and the state is not yet the continuation\'s'
        history.outcome(park) != null
        !history.requests(park).isEmpty()
        parked != expected

        when: 'the operator resumes the task'
        m.resume(TASK, definition, scenario, decision)
        String consumed = history.after(park).first()

        then: 'one commit after the park, landed by the continuation\'s own write'
        history.parent(consumed) == park
        history.subject(consumed) == subject

        and: 'outcome == null iff state.json carries the continuation\'s state — across the park and the continuation'
        [park, consumed].every {
            (history.outcome(it) == null) == (history.state(it) == expected)
        }
        history.outcome(consumed) == null

        and: 'the decision rides the same commit, or no decision is added'
        history.decisionBodies(consumed) == history.decisionBodies(park) + (decision == null ? [] : [decision])

        and: 'no decisions/ entry is left on the tip the continuation consumed'
        history.requests(consumed).isEmpty()

        // FR1, FR3, FR7 over every commit of the branch, the continued drive's included
        and: 'the task is one branch beside its factory register, which names the same commit (FR13 of tighten-spec-contract), and only a continuation write clears an outcome or resets the attempts'
        refsNaming(m.origin(), TASK).toSet() == [
            history.ref,
            "refs/gnomish-factory/${TASK}".toString()
        ].toSet()
        gitOutput(m.origin(), 'rev-parse', "refs/gnomish-factory/${TASK}") == gitOutput(m.origin(), 'rev-parse', history.ref)
        !history.after(consumed).isEmpty()
        def pairs = history.commits().collate(2, 1, false)
        pairs.findAll { p, c ->
            history.outcome(p) != null && history.outcome(c) == null
        }
        .every { p, c -> history.subject(c) in CONTINUATIONS }
        pairs.findAll { p, c -> resets(history.state(p), history.state(c)) }
        .every { p, c ->
            history.subject(c) in CONTINUATIONS && history.outcome(p) != null
        }

        and: 'FR5: every round that raises a stop records it in its own commit, and no later commit rewrites a record'
        def raised = pairs.collect { p, c ->
            appendedStop(history.state(p), history.state(c))
        }.findAll {
            it != null
        }
        raised.every { it }
        pairs.every { p, c -> keepsRecords(history.state(p), history.state(c)) }
        scenario != 'decision-needed' || !raised.isEmpty()

        where:
        medium | continuation | scenario | decision
        'host' | 'approveCheckpoint' | 'plain-round' | null
        'container' | 'approveCheckpoint' | 'plain-round' | null
        'host' | 'resumeFrom' | 'plain-round' | null
        'container' | 'resumeFrom' | 'plain-round' | null
        'host' | 'appendDecision' | 'decision-needed' | 'pg'
        'container' | 'appendDecision' | 'decision-needed' | 'pg'

        definition = pipelineFor(continuation)
        subject = continuation == 'approveCheckpoint' ? 'gnomish: task approved' : 'gnomish: task resumed'
    }

    private static final List<String> CONTINUATIONS = [
        'gnomish: task approved',
        'gnomish: task resumed'
    ]

    /** The task's refs on origin, by the task id in their name. */
    private List<String> refsNaming(Path origin, String taskId) {
        gitOutput(origin, 'for-each-ref', '--format=%(refname)').readLines().findAll {
            it.contains(taskId)
        }
    }

    /** The attempt history shortened at an unchanged position: a reset, never a stage advance. */
    private static boolean resets(TaskState before, TaskState after) {
        before != null && after != null && before.position() == after.position()
                && after.attempts().size() <before.attempts().size()
    }

    /**
     * For a commit that appends one round record at an unchanged position: whether a round that
     * escalates (a decision or a cannot-verify) carries its stop already; {@code null} otherwise.
     */
    private static Boolean appendedStop(TaskState before, TaskState after) {
        if (before == null || after == null || before.position() != after.position()
                || after.attempts().size() != before.attempts().size() + 1) {
            return null
        }
        AttemptRecord added = after.attempts().last()
        added.result() in [
            AttemptRecord.Result.DECISION_NEEDED,
            AttemptRecord.Result.CANNOT_VERIFY
        ]
        ? !(added.stop() instanceof Stop.None) : null
    }

    /** A commit at an unchanged position that does not reset keeps every earlier record as it was. */
    private static boolean keepsRecords(TaskState before, TaskState after) {
        before == null || after == null || before.position() != after.position() || resets(before, after) ||
                after.attempts().take(before.attempts().size()) == before.attempts()
    }

    private static PipelineDefinition pipelineFor(String continuation) {
        switch (continuation) {
            case 'approveCheckpoint': return ParkPipelines.pausing()
            case 'resumeFrom': return ParkPipelines.escalating()
            default: return ParkPipelines.asking()
        }
    }

    private static TaskState continuationState(String continuation, TaskState parked, PipelineDefinition definition) {
        if (continuation == 'approveCheckpoint') {
            def gate = parked.position() as Position.AwaitingApproval
            return new TaskState(BranchHistory.following(definition, gate.stage()), parked.attemptsUsed(), parked.attempts(), parked.totals())
        }
        new TaskState(parked.position(), 0, [], parked.totals())
    }
}
