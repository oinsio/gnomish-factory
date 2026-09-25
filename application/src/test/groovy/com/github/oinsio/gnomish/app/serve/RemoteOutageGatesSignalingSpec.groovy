package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.util.function.UnaryOperator
import spock.lang.Specification

/**
 * FR4, D6 of introduce-slot-wiring: {@link RemoteOutageGates#signaling} is the one place the
 * serve slot's git is decorated for the outage gate. What it returns is the caller's own bundle
 * with only its base-ref port replaced, and a base read through that port reports to the real
 * gate at the instant it returns (FR14 of add-base-ref-resolution). Asserted against a real gate
 * on virtual time, not "method calls method".
 */
class RemoteOutageGatesSignalingSpec extends Specification {

    private static final Path CLONE = Path.of('.')

    // FR4, D6: the composition root fills the slot wiring's git from here, so the copy must keep
    //     every other capability — store, branches, worktrees, mid-round push, epoch book — the
    //     undecorated bundle came with.
    def "signaling returns the same bundle with only its base-ref port replaced"() {
        given:
        def gate = RemoteOutageGateFixtures.closedGate()
        def push = UnaryOperator.identity()
        def git = new TaskGit(
                Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit), push, BaseRefGit.UNWIRED,
                new ClaimEpochBook())

        when:
        def decorated = RemoteOutageGates.signaling(git, gate)

        then:
        decorated.store().is(git.store())
        decorated.branches().is(git.branches())
        decorated.worktrees().is(git.worktrees())
        decorated.midRoundPush().is(push)
        decorated.epochs().is(git.epochs())
        decorated.baseRefs() instanceof RemoteOutageSignalingBaseRefGit
        decorated.baseRefs().delegate().is(BaseRefGit.UNWIRED)
        decorated.baseRefs().gate().is(gate)
    }

    // FR14 of add-base-ref-resolution: through the decorated port, a claim-time refresh that met
    //     an outage opens the gate the serve assembly point handed in, and the outcome is
    //     forwarded unchanged.
    def "a base read through the decorated git reports to the gate"() {
        given:
        def gate = RemoteOutageGateFixtures.closedGate()
        def outage = new BaseRefreshOutcome.Unavailable(UntrustedText.subprocess('connection refused'))
        def remote = [refresh: { Path d, String r ->
                outage
            }] as BaseRefGit
        def git = new TaskGit(
                Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit), UnaryOperator.identity(), remote,
                new ClaimEpochBook())

        when:
        def outcome = RemoteOutageGates.signaling(git, gate).baseRefs().refresh(CLONE, 'main')

        then:
        outcome.is(outage)
        gate.isOpen()
        gate.health().lastError() == 'connection refused'
    }
}
