package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR4, NFR-R1, NFR-O1 of fix-terminal-receipt-convergence (design D2): the one cleanup of a
 * delivered branch — the cleanup commit and the medium's disposal on a {@code CompletedUncleaned}
 * tip, nothing on any other tip, a lost compare-and-swap ended quietly, and the claimless entry
 * gated on the one finish reading.
 */
class DeliveredCleanupSpec extends Specification {

    private static final BranchAdoption.AlreadyLocal BRANCH = new BranchAdoption.AlreadyLocal('PROJ 1')

    private static final Path CLONE_DIR = Path.of('/clones/widgets')
    private static final TaskRef REF = new TaskRef('PROJ 1')
    private static final InstanceId US = new InstanceId('gnomish', 'ab12cd')

    TaskBranchGit branches = Mock(TaskBranchGit)
    TaskLifecycleStore store = Mock(TaskLifecycleStore)
    TaskEnvironmentDisposal disposal = Mock(TaskEnvironmentDisposal)
    DeliveredCleanup cleanup = new DeliveredCleanup(branches, store, disposal)

    // FR4: the cleanup commit lands, then the workspace keyed by the sanitized id goes, in that order
    def "FR4: a CompletedUncleaned tip gets the cleanup commit, then the medium's disposal"() {
        given:
        def logs = LogCaptureSupport.attach(DeliveredCleanup, Level.INFO)

        when:
        cleanup.cleanUp(CLONE_DIR, BRANCH)

        then:
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1') >> new BranchShape.CompletedUncleaned()

        then:
        1 * store.finishCleanup('PROJ 1')

        then:
        1 * disposal.dispose('PROJ-1')

        and: 'NFR-O1: one INFO line names the task'
        logs.list.findAll {
            it.level == Level.INFO
        }*.formattedMessage == [
            'delivered branch of PROJ 1 cleaned up'
        ]

        cleanup:
        logs.detach()
    }

    // FR4, NFR-R1: every other tip is left alone — a Delivered tip is already cleaned, the rest are
    //     not deliveries; a second run over a cleaned tip therefore writes nothing
    def "FR4: a #shape.class.simpleName tip is a no-op"() {
        when:
        cleanup.cleanUp(CLONE_DIR, BRANCH)

        then:
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1') >> shape
        0 * store._
        0 * disposal._

        where:
        shape << [
            new BranchShape.Delivered(),
            new BranchShape.Bare(),
            new BranchShape.Created(),
            new BranchShape.InProgress(),
            new BranchShape.AwaitingApproval(),
            new BranchShape.Parked(),
            new BranchShape.Answered(),
            new BranchShape.Unknown('unreadable'),
            new BranchShape.Unregistered('refs/gnomish-factory/PROJ-1', 'b' * 40),
            new BranchShape.Corrupt('refused'),
            new BranchShape.UnsupportedVersion('task.json', 9, 1),
        ]
    }

    // NFR-R1: the loser of a compare-and-swap race reads the winner's cleanup at the tip and stops
    def "NFR-R1: a cleanup that loses the ref race to another writer logs DEBUG and returns"() {
        given:
        def logs = LogCaptureSupport.attach(DeliveredCleanup, Level.DEBUG)
        def lost = failure()

        when:
        cleanup.cleanUp(CLONE_DIR, BRANCH)

        then:
        2 * branches.classifyShape(CLONE_DIR, 'PROJ 1') >>> [
            new BranchShape.CompletedUncleaned(),
            new BranchShape.Delivered()
        ]
        1 * store.finishCleanup('PROJ 1') >> { throw lost }
        0 * disposal._
        notThrown(GitTaskRepositoryException)

        and:
        def line = logs.list.find {
            it.formattedMessage.contains('lost the race')
        }
        line.level == Level.DEBUG
        line.throwableProxy.message == lost.message
        logs.list.every { it.level == Level.DEBUG }

        cleanup:
        logs.detach()
    }

    // FR4: a failure that leaves the tip uncleaned is not a lost race — it reaches the caller
    def "FR4: a cleanup failure that leaves the tip uncleaned propagates and disposes nothing"() {
        given:
        def broken = failure()

        when:
        cleanup.cleanUp(CLONE_DIR, BRANCH)

        then:
        2 * branches.classifyShape(CLONE_DIR, 'PROJ 1') >> new BranchShape.CompletedUncleaned()
        1 * store.finishCleanup('PROJ 1') >> { throw broken }
        0 * disposal._
        def thrown = thrown(GitTaskRepositoryException)
        thrown.is(broken)
    }

    // FR4: the claimless entry cleans when the one finish reading says the finish landed
    def "FR4: a claimless caller cleans when the tracker reports the task finished (#how)"() {
        when:
        cleanup.cleanUpIfFinished(CLONE_DIR, BRANCH, observed)

        then:
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1') >> new BranchShape.CompletedUncleaned()
        1 * store.finishCleanup('PROJ 1')
        1 * disposal.dispose('PROJ-1')

        where:
        how | observed
        'the finished fact' | TrackerTaskFixtures.heldAfter(REF, US, null, true)
        'a FINISH tenure boundary' | TrackerTaskFixtures.heldAfter(REF, US, BoundaryKind.FINISH)
    }

    // FR4: an unfinished task is never cleaned — not even its tip is read
    def "FR4: a claimless caller whose tracker does not report the task finished touches nothing (#boundary)"() {
        when:
        cleanup.cleanUpIfFinished(CLONE_DIR, BRANCH, TrackerTaskFixtures.heldAfter(REF, US, boundary))

        then:
        0 * branches._
        0 * store._
        0 * disposal._

        where:
        boundary << [
            null,
            BoundaryKind.PARK,
            BoundaryKind.ABORT,
            BoundaryKind.STALE_CLAIM_REMOVED
        ]
    }

    private static GitTaskRepositoryException failure() {
        new GitTaskRepositoryException('PROJ 1', TaskLifecycleEvent.COMPLETED, 'closing ref transaction',
                UntrustedText.subprocess('cannot lock ref'))
    }
}
