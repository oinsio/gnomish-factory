package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import spock.lang.Specification

/**
 * FR5, FR9, UX2 of add-tracker-port: the explicit-mode disposition matrix — what {@code take <ref>}
 * does with a task in each tracker state. Every non-{@code Ready} state has its own answer, and the
 * refusals are operator-facing text that has to say what is wrong AND how to get the task moving,
 * since an operator who typed an explicit ref expects the task to be taken.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules): the disposition itself makes
 * no git call, so the claim chain below it is never reached by the refusal paths.
 *
 * <p>Named for the matrix rather than the class so it does not collide with the composition
 * root's own {@code TakeDispositionSpec}, which drives the same class end to end.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class TakeDispositionMatrixSpec extends Specification implements RunChainFakes {

    private Tracker tracker = Mock(Tracker)

    private TakeDisposition disposition() {
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.NotFound()
        }, Stub(TaskWorktreeGit))
        new TakeDisposition(Stub(RunAssembly), git, WORKTREES_ROOT,
                new AbortHandler(tracker, FIXED_CLOCK), 3, 'taskId', [], ClaimBeat.NONE, false, { _ref, _holder, _age ->
                    TakeoverConfirmation.Decision.DECLINED
                } as TakeoverConfirmation,
                FIXED_CLOCK, new ClaimLossFlag(), ContainerTakeSupport.hostOnly())
    }

    private TakeResult dispose(TrackerTask task) {
        disposition().dispose(CLONE_DIR, null, pipeline(), RunArguments.InteractiveMode.NONE, false,
                task, tracker, INSTANCE)
    }

    private static TrackerTask taskIn(TrackerTaskState state, boolean finished = false) {
        new TrackerTask(REF, new TaskSnapshot('PROJ-1', 'title', 'body'), state, AbortFacts.none(), finished)
    }

    // FR5: a REOPENED finished task is refused through the decline protocol, never claimed — the
    // factory told the tracker this task was done, and re-taking it would silently undo that.
    def "declines a reopened finished task instead of claiming it"() {
        when:
        def result = dispose(taskIn(new TrackerTaskState.Ready(), true))

        then: 'the decline is posted to the tracker, and no claim is attempted'
        1 * tracker.declineFinished(REF, _)
        0 * tracker.claim(_, _)

        and:
        result instanceof TakeResult.Skipped
        result.reason().contains('already finished')
    }

    // FR9: an ordinary Ready task is the one state that actually claims.
    def "claims an ordinary ready task"() {
        when:
        def result = dispose(taskIn(new TrackerTaskState.Ready()))

        then:
        1 * tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Held('someone-else')
        result instanceof TakeResult.Skipped
    }

    // FR9, FR6 of add-claim-heartbeat: a task held by someone else goes through the takeover gate,
    // not a flat refusal — here the confirmation declines, so it refuses naming the holder.
    def "routes a task held by another instance through the takeover gate"() {
        when:
        def result = dispose(taskIn(new TrackerTaskState.Working('gnomish-other-99xxyy')))

        then:
        1 * tracker.listOpen() >> []
        0 * tracker.claim(_, _)
        result instanceof TakeResult.Skipped
        result.reason().contains('gnomish-other-99xxyy')
    }

    // FR9, UX2: a parked task is refused with the reason AND the way back. The three park reasons
    // need different return paths, so a single generic message would leave the operator stuck.
    def "refuses a parked task naming the reason and the way back to ready"() {
        when:
        def result = dispose(taskIn(new TrackerTaskState.AwaitingHuman(reason)))

        then:
        0 * tracker.claim(_, _)
        result instanceof TakeResult.Skipped
        result.reason().contains(reason.toString())
        result.reason().contains(expectedHint)

        where:
        reason || expectedHint
        ParkReason.ESCALATION || 'reply in the tracker'
        ParkReason.CHECKPOINT || 'manual checkpoint'
        ParkReason.INFRA || 'environment or pipeline problem'
    }

    // FR9: the two terminal states are refused with distinct messages — "done" and "does not exist"
    // are different facts, and an operator who typed a ref needs to know which one they hit.
    def "refuses finished and gone tasks with their own distinct messages"() {
        when:
        def result = dispose(taskIn(state))

        then:
        0 * tracker.claim(_, _)
        result instanceof TakeResult.Skipped
        result.reason().contains(expected)

        where:
        state || expected
        new TrackerTaskState.Finished() || 'already done (Finished)'
        new TrackerTaskState.Gone(null) || 'closed or does not exist'
    }
}
