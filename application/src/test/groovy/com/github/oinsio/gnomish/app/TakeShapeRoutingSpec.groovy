package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.branch.BranchQuarantineException
import com.github.oinsio.gnomish.app.branch.BranchRecoveryFailedException
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.BranchShape
import spock.lang.Specification

/**
 * FR2, FR15 of harden-task-branch-contract: the resume table routes on the branch's classified
 * shape and nothing else, and the three shapes no automatic recovery can converge stop the run
 * with their own diagnosis rather than being resumed into a parse failure.
 */
class TakeShapeRoutingSpec extends Specification implements RunChainFakes {

    private void route(BranchShape shape) {
        def mechanics = Stub(ResumeMechanics)
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit), new ClaimEpochBook())
        new TakeDispositionResume(mechanics, new TakeDecisionResume(mechanics), git)
                .resumeExisting(takeOrder(heldByUs(), Stub(Tracker)), shape)
    }

    // All three shapes are non-clean, so each verdict is raised inside the repair boundary of
    // resumeExisting: the assertion below is also FR15's "a quarantine keeps its own
    // classification" — it is a decision about the branch, never a failed repair to be retried.
    def "FR15: #description is quarantined, never resumed"() {
        when:
        route(shape)

        then:
        def ex = thrown(BranchQuarantineException)
        ex.message.contains('PROJ-1')
        ex.message.contains(fragment)

        where:
        description | shape | fragment
        'an unsupported state version' | new BranchShape.UnsupportedVersion('state.json', 9, 1) | 'state.json declaring version 9'
        'unreadable content' | new BranchShape.Corrupt('task.json: bad json') | 'corrupt content'
        'an unrecognized combination' | new BranchShape.Unknown('state without task') | 'unrecognized combination'
    }

    // FR14 of harden-task-branch-contract: a failure while repairing a non-clean shape is named as
    // a failed recovery here — the one place that knows a repair was underway — so the crash
    // boundary can spend the right category of the unified accounting.
    def "FR14: a failure repairing a non-clean shape is named a failed recovery"() {
        given: 'loading the branch of a parked tip fails'
        def mechanics = Stub(ResumeMechanics) {
            loadBranch(_, _) >> {
                throw new IllegalStateException('fetch exploded')
            }
        }
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit), new ClaimEpochBook())

        when:
        new TakeDispositionResume(mechanics, new TakeDecisionResume(mechanics), git)
                .resumeExisting(takeOrder(heldByUs(), Stub(Tracker)), new BranchShape.Parked())

        then:
        def failed = thrown(BranchRecoveryFailedException)
        failed.shape() instanceof BranchShape.Parked
        failed.cause.message == 'fetch exploded'
        failed.message.contains('PROJ-1')
    }

    // Bare is the fresh-claim route's shape and is decided before resume is ever reached, so
    // arriving here with it is a routing defect — the table says so instead of failing later on a
    // branch with nothing on it.
    def "FR2: a Bare shape never reaches the resume table"() {
        when:
        route(new BranchShape.Bare())

        then:
        thrown(BranchQuarantineException)
    }
}
