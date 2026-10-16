package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.DeliveredCleanupMedium.HOST

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.DivergedBranchException
import com.github.oinsio.gnomish.app.port.git.LocalTaskBranch
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Design D12 of fix-terminal-receipt-convergence (FR9, NFR-R1, NG4): a lost cleanup race between
 * two clones is not undone. Two instances of one project, each its own factory home, clone and
 * {@code TaskGit} bundle over one bare {@code origin} and one tracker ({@link
 * DeliveredCleanupWorld#secondClone}), real adapters throughout.
 *
 * <p>The race: B's claimless cleanup locates the branch — a remote-tracking ref its touchpoint
 * fetch just refreshed — and A delivers the cleanup before B adopts. B's own steps are then driven
 * as {@link ClaimlessDeliveredCleanup} runs them (adopt over the location it holds, then the host
 * {@link DeliveredCleanup}), the one window production leaves between the fetch and the push. B's
 * push is rejected; its head and register stay at its own cleanup commit. Then the four readers of
 * that line: the next claimless pass, the touchpoint reconciliation, a manual {@code run --resume},
 * and a claimed take after the tracker lost the finish. None of them deletes, moves or force-pushes
 * the line: it has no recovery owner by design, and costs one ref.
 *
 * <p>The kill-point table covers the claimless cleanup over an adopted head with no race, windows
 * (a)–(c) of D12, each picked up by the owner the design names for it.
 *
 * <p>FR9, NFR-R1 of fix-terminal-receipt-convergence.
 */
class DeliveredCleanupCrossCloneSpec extends Specification {

    static final String TASK = DeliveredCleanupWorld.TASK_ID

    @TempDir
    Path tempDir

    // Scenario: git/git-task-persistence / A lost cleanup race leaves the adopted head in place
    def "FR9: B's cleanup push on an adopted head loses to A's delivery; the line stands and the next claimless pass writes nothing"() {
        given: 'A delivers between B locating the branch and B adopting it'
        def (a, b) = worlds()
        def located = b.git.branches().locate(b.projectDir, TASK)
        assert located instanceof BranchLocation.RemoteTracking
        assert new DeliveredCleanupEntries(a).take() == 15
        String delivered = a.gitOutput(a.origin, 'rev-parse', DeliveredCleanupWorld.branchRef())

        when: 'B adopts over the location it holds and cleans'
        def warnings = LogCaptureSupport.attach('com.github.oinsio.gnomish', Level.WARN)
        def branch = b.git.branches().adopt(b.projectDir, TASK, located)
        DeliveredCleanup.host(b.git, b.clone).cleanUp(b.projectDir, branch as LocalTaskBranch)
        warnings.detach()

        then: "B's head and register stay at B's own cleanup commit, one WARN says the push was refused"
        branch == new BranchAdoption.Adopted(TASK)
        def lost = b.footprint()
        lost.tip == lost.register
        lost.tip != delivered
        b.subjectOf(b.projectDir, lost.tip) == ServiceCommitMessages.cleanup()
        warnings.list.size() == 1

        and: "origin holds A's one cleanup commit and its register"
        lost.originTip == delivered && lost.originRegister == delivered
        a.gitOutput(a.origin, 'log', '--format=%s', delivered).readLines().count(ServiceCommitMessages.cleanup()) == 1

        when: "B's next claimless pass"
        def quiet = LogCaptureSupport.attach('com.github.oinsio.gnomish', Level.WARN)
        def exit = new DeliveredCleanupEntries(b).take()
        quiet.detach()

        then: 'it reads Delivered, writes nothing and runs no push — a push of that line would be refused with a WARN'
        exit == 15
        b.shape() == 'Delivered'
        b.footprint().subMap('tip', 'register', 'originTip', 'originRegister') == lost.subMap('tip', 'register', 'originTip', 'originRegister')
        quiet.list.isEmpty()
    }

    // Scenario: git/git-task-persistence / A diverged adopted head is left standing by every reader
    def "FR9, NG4: the touchpoint declines the line and a manual resume fails closed on it, deleting nothing"() {
        given: "B's line after a lost cleanup race"
        def (a, b) = lostRace()
        def lost = b.footprint()

        when: "B's touchpoint reconciliation"
        def reconciliation = LogCaptureSupport.attach('com.github.oinsio.gnomish.adapter.git.OriginReconciliation', Level.WARN)
        b.git.branches().reconcileRemote(b.projectDir, TASK, 'resume-start')
        reconciliation.detach()

        then: 'it declines the catch-up push and says why'
        reconciliation.list.size() == 1
        reconciliation.list[0].formattedMessage.contains('not an ancestor of the local tip ' + lost.tip)
        b.footprint() == lost

        when: '`run --resume` on B'
        new DeliveredCleanupEntries(b).resume()

        then: 'it fails closed on the divergence and deletes nothing'
        thrown(DivergedBranchException)
        b.footprint().subMap('tip', 'register', 'originTip', 'originRegister') == lost.subMap('tip', 'register', 'originTip', 'originRegister')
    }

    // Scenario: git/git-task-persistence / A diverged adopted head is left standing by every reader
    def "FR9, NG4: a claimed take on B after the tracker lost the finish reports the task done and leaves the line standing"() {
        given: "B's line after a lost cleanup race, and the task back at work with no finish on record"
        def (a, b) = lostRace()
        def lost = b.footprint()
        b.trackerReady()

        when: 'B takes it under a claim'
        def exit = new DeliveredCleanupEntries(b).take()

        then: 'it reads Delivered and reports the task done'
        exit == 0
        b.tracker.fetchTask(DeliveredCleanupWorld.REF).state() instanceof TrackerTaskState.Finished

        and: "B's head and register stay at B's cleanup commit, and origin is unchanged"
        b.footprint().subMap('tip', 'register', 'originTip', 'originRegister') == lost.subMap('tip', 'register', 'originTip', 'originRegister')
    }

    // Scenario: git/git-task-persistence / A cleanup push that does not reach origin keeps the adopted head
    def "FR9, NFR-R1: killed #window, the next #pickup converges the adopted head and the next writes nothing"() {
        given: 'A never cleans; B holds the finished branch only as a remote-tracking ref'
        def (a, b) = worlds()
        def branch = b.git.branches().adopt(b.projectDir, TASK, b.git.branches().locate(b.projectDir, TASK))
        assert branch == new BranchAdoption.Adopted(TASK)
        steps.call(b, branch)

        when: 'the pickup runs, then runs again'
        new DeliveredCleanupEntries(b)."$pickup"()
        def converged = b.footprint()
        new DeliveredCleanupEntries(b)."$pickup"()

        then: "B's head, its register, origin's branch and origin's register name one cleanup commit"
        b.shape() == 'Delivered'
        converged.subMap('register', 'originTip', 'originRegister').values().toSet() == [converged.tip] as Set
        b.subjects().count(ServiceCommitMessages.cleanup()) == 1
        b.subjectOf(b.projectDir, converged.tip) == ServiceCommitMessages.cleanup()

        and: 'the third run writes nothing'
        b.footprint() == converged

        where: 'each window is picked up by the owner D12 names: the claimless pass, or the touchpoint for an unpushed commit'
        window | pickup | steps
        '(a) after the create' | 'take' | { w, br -> }
        '(b) after the cleanup commit' | 'resume' | strictCleanup()
        '(c) after the push' | 'take' | pushedCleanup()
    }

    /** A finished task whose branch owes its cleanup, at origin; B a second clone holding none of it locally. */
    private List<DeliveredCleanupWorld> worlds() {
        def a = new DeliveredCleanupWorld(tempDir.resolve('a'), HOST)
        a.completedUncleaned()
        a.trackerFinished()
        def b = a.secondClone(tempDir.resolve('b'))
        [a, b]
    }

    /** The race of the first feature, settled: B's cleanup commit on its adopted head, origin at A's. */
    private List<DeliveredCleanupWorld> lostRace() {
        def (a, b) = worlds()
        def located = b.git.branches().locate(b.projectDir, TASK)
        assert new DeliveredCleanupEntries(a).take() == 15
        def branch = b.git.branches().adopt(b.projectDir, TASK, located) as LocalTaskBranch
        DeliveredCleanup.host(b.git, b.clone).cleanUp(b.projectDir, branch)
        assert b.footprint().tip != b.footprint().originTip
        [a, b]
    }

    /** The cleanup commit by the strict store, which pushes nothing: the kill lands before the push. */
    private static Closure strictCleanup() {
        { DeliveredCleanupWorld w, LocalTaskBranch br ->
            new GitTaskRepository(new GitProcessRunner(), w.clone, new ClaimEpochBook(), new VirtualClock()).finishCleanup(TASK)
        }
    }

    /** The cleanup commit and its push, as the claimless cleanup writes them. */
    private static Closure pushedCleanup() { {
            DeliveredCleanupWorld w, LocalTaskBranch br ->
            DeliveredCleanup.host(w.git, w.clone).cleanUp(w.projectDir, br)
        }
    }
}
