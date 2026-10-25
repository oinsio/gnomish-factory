package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException
import com.github.oinsio.gnomish.app.port.git.BranchStateResult
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskListRow
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR14 of add-base-ref-resolution, finding F88 of tighten-spec-contract: the task-branch lookup a
 * serve slot reads through reports "origin could not be asked" to the real gate at the instant it
 * returns — the lookup is a fresh claim's first read of origin, so on a dead remote it is the read
 * that meets the outage. Every other answer, success included, leaves the gate alone: a lookup's
 * result does not say whether origin was contacted. Asserted against a real gate on virtual time.
 */
class RemoteOutageSignalingTaskBranchGitSpec extends Specification {

    private static final Path CLONE = Path.of('clone')
    private static final String TASK = 'PROJ-1'
    private static final String CAUSE = 'connection refused'

    def gate = DaemonConditionFixtures.closedGate()
    def delegate = Mock(TaskBranchGit)
    def decorated = new RemoteOutageSignalingTaskBranchGit(delegate, gate)

    // F88: each lookup built on the branch location throws the outage; it opens the gate with the
    //     lookup's reason and reaches the slot's release path unchanged.
    def "a lookup that could not reach origin opens the gate and rethrows: #lookup"() {
        given:
        def outage = new BranchLocationUnavailableException(TASK, UntrustedText.subprocess(CAUSE))

        when:
        call(lookup)

        then:
        1 * delegate."$lookup"(CLONE, TASK) >> { throw outage }
        def thrown = thrown(BranchLocationUnavailableException)
        thrown.is(outage)
        gate.isOpen()
        gate.health().lastError() == CAUSE

        where:
        lookup << [
            'classifyShape',
            'ensureLocalTaskBranch',
            'readState',
            'readDelivered'
        ]
    }

    // F88: the same fact returned rather than thrown — locate answers Unavailable.
    def "locate answering Unavailable opens the gate and returns the answer unchanged"() {
        given:
        def unavailable = new BranchLocation.Unavailable(UntrustedText.subprocess(CAUSE))
        delegate.locate(CLONE, TASK) >> unavailable

        expect:
        decorated.locate(CLONE, TASK).is(unavailable)
        gate.isOpen()
        gate.health().lastError() == CAUSE
    }

    // FR14: an answered lookup is forwarded and signals nothing — not even a confirmed refresh,
    //     since a branch found in the clone was never asked of origin.
    def "an answered lookup is forwarded and leaves the gate closed: #lookup"() {
        given:
        delegate."$lookup"(CLONE, TASK) >> answer

        expect:
        call(lookup).is(answer)
        !gate.isOpen()
        gate.health().lastSuccessAt() == null

        where:
        lookup | answer
        'classifyShape' | new BranchShape.InProgress()
        'readState' | new BranchStateResult.NotFound()
        'readDelivered' | new DeliveredBranchState(null, null)
        'locate' | new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
    }

    // FR11 of fix-terminal-receipt-convergence (design D13 as amended for task 6.6): the read over a
    //     caller's location is wrapped like the other lookups — its answer is forwarded unchanged with
    //     the gate left closed, and its outage opens the gate and is rethrown unchanged.
    def "classifyShape over a location forwards the answer, and its outage opens the gate"() {
        given:
        def tracking = new BranchLocation.RemoteTracking('refs/remotes/origin/gnomish/PROJ-1')
        def unavailable = new BranchLocation.Unavailable(UntrustedText.subprocess(CAUSE))
        def outage = new BranchLocationUnavailableException(TASK, UntrustedText.subprocess(CAUSE))
        def answer = new BranchShape.CompletedUncleaned()
        delegate.classifyShape(CLONE, TASK, tracking) >> answer
        delegate.classifyShape(CLONE, TASK, unavailable) >> { throw outage }

        expect:
        decorated.classifyShape(CLONE, TASK, tracking).is(answer)
        !gate.isOpen()

        when:
        decorated.classifyShape(CLONE, TASK, unavailable)

        then:
        def thrown = thrown(BranchLocationUnavailableException)
        thrown.is(outage)
        gate.isOpen()
        gate.health().lastError() == CAUSE
    }

    // FR7 of fix-terminal-receipt-convergence: the typed answer passes through unchanged.
    def "ensureLocalTaskBranch forwards the delegate's answer: #found"() {
        given:
        delegate.ensureLocalTaskBranch(CLONE, TASK) >> found

        expect:
        decorated.ensureLocalTaskBranch(CLONE, TASK) == found
        !gate.isOpen()

        where:
        found << [
            new BranchAdoption.Adopted(TASK),
            new BranchAdoption.AlreadyLocal(TASK),
            new BranchAdoption.Nowhere()
        ]
    }

    // FR8 of fix-terminal-receipt-convergence: the adoption over a located branch asks no remote of
    // its own, so it passes through and signals nothing — even when the delegate throws Unavailable.
    def "adopt forwards the delegate's answer and never signals the gate"() {
        given:
        def location = new BranchLocation.RemoteTracking('refs/remotes/origin/gnomish/PROJ-1')
        def adopted = new BranchAdoption.Adopted(TASK)
        def unavailable = new BranchLocation.Unavailable(UntrustedText.subprocess(CAUSE))
        delegate.adopt(CLONE, TASK, location) >> adopted
        delegate.adopt(CLONE, TASK, unavailable) >> {
            throw new BranchLocationUnavailableException(TASK, UntrustedText.subprocess(CAUSE))
        }

        expect:
        decorated.adopt(CLONE, TASK, location).is(adopted)

        when:
        decorated.adopt(CLONE, TASK, unavailable)

        then:
        thrown(BranchLocationUnavailableException)
        !gate.isOpen()
    }

    // FR14: the operations that are not lookups pass through untouched.
    def "the other operations reach the delegate unchanged"() {
        given:
        def rows = [
            new TaskListRow(TASK, null, 0, null, new BranchShape.Bare())
        ]
        def verdict = new ParkDeliveryVerdict.Undelivered('note')

        when:
        decorated.requireRefFormat(CLONE)
        decorated.pushClosingBestEffort(CLONE, 'PROJ-1')
        decorated.reconcileRemote(CLONE, TASK, 'resume-start')
        def listed = decorated.list(CLONE)
        def fenced = decorated.fenceParkDelivery(CLONE, TASK)

        then:
        1 * delegate.requireRefFormat(CLONE)
        1 * delegate.pushClosingBestEffort(CLONE, 'PROJ-1')
        1 * delegate.reconcileRemote(CLONE, TASK, 'resume-start')
        1 * delegate.list(CLONE) >> rows
        1 * delegate.fenceParkDelivery(CLONE, TASK) >> verdict
        listed.is(rows)
        fenced.is(verdict)
        !gate.isOpen()
    }

    private Object call(String lookup) {
        decorated."$lookup"(CLONE, TASK)
    }
}
