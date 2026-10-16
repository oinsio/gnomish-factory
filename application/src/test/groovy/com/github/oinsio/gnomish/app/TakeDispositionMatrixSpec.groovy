package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
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
        disposition(new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.NotFound()
            adopt(_, _, _) >> { Path clone, String taskId, BranchLocation location ->
                new BranchAdoption.AlreadyLocal(taskId)
            }
            classifyShape(_, _) >> new BranchShape.Bare()
        }, Stub(TaskWorktreeGit), new ClaimEpochBook()))
    }

    private TakeDisposition disposition(TaskGit git) {
        new TakeDisposition(slotWiring(Stub(RunAssembly) {
            timeEquipment() >> VirtualTimeEquipment.on(new VirtualClock(NOW))
        }, git, tracker, CLONE,
        ContainerTakeSupportFixture.hostOnly(), new ClaimTenure(ClaimBeat.NONE, new ClaimLossFlag()),
        // The claim never reaches a fresh-claim base resolution in this spec: every Ready
        // scenario is stopped at tracker.claim() itself (see disposition() call sites), so any
        // well-formed branch name does. It is a name and not the clone's checkout because
        // DefaultBranch refuses the HEAD stand-in outright (FR4, FR10, M2).
        new TrustedBaseContext(BaseDefinition.none(), new DefaultBranch('main'))),
        false, { _ref, _holder, _age ->
            TakeoverConfirmation.Decision.DECLINED
        } as TakeoverConfirmation)
    }

    private TakeResult dispose(TrackerTask task) {
        disposition().dispose(takeOrder(task, tracker))
    }

    TaskBranchGit branches = Mock(TaskBranchGit)
    TaskLifecycleStore store = Mock(TaskLifecycleStore)
    TaskEnvironmentDisposal worktreeDisposal = Mock(TaskEnvironmentDisposal)

    /** A disposition over the mocked branch port, the host store and the worktree disposal. */
    private TakeDisposition dispositionOverBranches() {
        disposition(new TaskGit(Stub(TaskStoreGit) {
            taskRepository(CLONE) >> store
        }, branches, Stub(TaskWorktreeGit) {
            environmentDisposal(CLONE) >> worktreeDisposal
        }, new ClaimEpochBook()))
    }

    private static TrackerTask taskIn(TrackerTaskState state, boolean finished = false) {
        new TrackerTask(REF, new TaskSnapshot('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body')), state, AbortFacts.none(), finished)
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
    // Scenario: tracker/tracker-take / Parked task is refused
    // Scenario: tracker/tracker-port / Parked task returns only through a human
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

    // FR4 of fix-terminal-receipt-convergence: the Finished arm converges a delivered branch the
    // finish left uncleaned over the task it already fetched — the cleanup commit, then the
    // worktree — writes no claim, and still reports the task done.
    // Scenario: tracker/tracker-take / A finished task's cleanup converges without a claim
    def "FR4: a Finished task's uncleaned delivered branch is cleaned without a claim, then skipped as done"() {
        given:
        def disposition = dispositionOverBranches()

        when:
        def result = disposition.dispose(takeOrder(taskIn(new TrackerTaskState.Finished(), true), tracker))

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ-1') >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-1', new BranchLocation.Local('refs/heads/gnomish/PROJ-1')) >>
                new BranchShape.CompletedUncleaned()
        1 * branches.adopt(CLONE_DIR, 'PROJ-1', _) >> new BranchAdoption.AlreadyLocal('PROJ-1')
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-1') >> new BranchShape.CompletedUncleaned()

        then:
        1 * store.finishCleanup('PROJ-1')

        then:
        1 * worktreeDisposal.dispose('PROJ-1')
        0 * tracker._

        and:
        result instanceof TakeResult.Skipped
        result.reason().contains('already done (Finished)')
    }

    // FR4, FR5 of enforce-finish-terminality: a reopened finished task is declined first, then its
    // delivered branch converges — the decline is the arm's tracker write, the cleanup its git one.
    def "FR4: a reopened finished task is declined, then its uncleaned delivered branch is cleaned"() {
        given:
        def disposition = dispositionOverBranches()

        when:
        def result = disposition.dispose(takeOrder(taskIn(new TrackerTaskState.Ready(), true), tracker))

        then:
        1 * tracker.declineFinished(REF, _)

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ-1') >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-1', new BranchLocation.Local('refs/heads/gnomish/PROJ-1')) >>
                new BranchShape.CompletedUncleaned()
        1 * branches.adopt(CLONE_DIR, 'PROJ-1', _) >> new BranchAdoption.AlreadyLocal('PROJ-1')
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-1') >> new BranchShape.CompletedUncleaned()
        1 * store.finishCleanup('PROJ-1')
        1 * worktreeDisposal.dispose('PROJ-1')
        0 * tracker.claim(_, _)

        and:
        result instanceof TakeResult.Skipped
        result.reason().contains('already finished')
    }

    // FR4: an arm that is not finished never reaches the cleanup — no branch lookup at all
    def "FR4: a #state.class.simpleName task never looks up the branch"() {
        given:
        def disposition = dispositionOverBranches()

        when:
        disposition.dispose(takeOrder(taskIn(state), tracker))

        then:
        0 * branches._
        0 * store._

        where:
        state << [
            new TrackerTaskState.Gone(null),
            new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION)
        ]
    }
}
