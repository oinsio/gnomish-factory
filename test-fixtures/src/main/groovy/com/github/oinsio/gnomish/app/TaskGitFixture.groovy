package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskBackend
import com.github.oinsio.gnomish.adapter.git.VirtualTimeGitRetries
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.InstantSource
import java.util.function.UnaryOperator

/**
 * The real git-backed {@link TaskGit} a spec passes wherever production wiring injects the bean
 * (FR12b, design D12 of split-into-modules). These specs drive real local clones, so they want the
 * real backend — the port exists to keep {@code application} off the adapter's types, not to make
 * every spec fake git.
 *
 * <p>One {@link GitProcessRunner} per instance, matching the production bean: the runner is what
 * serializes repo-level mutating commands per clone.
 */
final class TaskGitFixture {

    private TaskGitFixture() {}

    /**
     * A {@link TaskGit} over the real {@code git} binary wired the way production wires it (FR4,
     * design D3 of fix-claim-epoch-fence): ONE {@link ClaimEpochBook} is both the bundle's tenure
     * record and the source its writers stamp from, so a spec that claims observes the same
     * stamped commits and the same recorded epochs an operator would. The claim itself fills the
     * book, through the decorator {@code TrackerResolution.resolveTracker} applies over the
     * tracker the command resolves from this bundle — nothing here has to be wired by hand.
     *
     * <p>A spec that never claims gets no stamp from this either: an unfilled book answers empty
     * for every task, exactly as the plain {@code gnomish run} path does in production.
     */
    static TaskGit real() {
        real(new GitProcessRunner())
    }

    /**
     * The claim-stamping bundle over a caller's runner — a recording or refusing git stand-in —
     * for a spec that must see, or forbid, what the bundle spends (FR10 of own-git-transfer-argv:
     * the floor refusal is proven to precede every transfer by handing every git-backed port the
     * same fake).
     */
    static TaskGit real(GitProcessRunner runner) {
        build(runner, new ClaimEpochBook())
    }

    /**
     * The claim-stamping bundle whose writers date their commits by {@code clock} — for a spec
     * running two instances that each write one commit over one parent: on one shared virtual
     * epoch the two would be byte-identical objects, which two real instances' commits never are
     * (design D12 of fix-terminal-receipt-convergence).
     */
    static TaskGit realAt(InstantSource clock) {
        build(new GitProcessRunner(), new ClaimEpochBook(), clock)
    }

    /**
     * A {@link TaskGit} for the commands that never claim — {@code status}, {@code usage},
     * {@code board} — where the distinction is the point of the spec; every other spec wants
     * {@link #real()}. The bundle still carries a tenure record, because no {@link TaskGit} may be
     * built without one (FR4 of fix-claim-epoch-fence), and its writers stamp from that record, the
     * only shape the assembly admits (F74 of tighten-spec-contract): what makes it claimless is that
     * nothing ever fills the book, since the commands it serves wrap no tracker with it, so every
     * task answers empty and no commit is stamped — exactly a git layer that holds no tenure.
     */
    static TaskGit realClaimless() {
        build(new GitProcessRunner(), new ClaimEpochBook())
    }

    /**
     * The one construction every form shares: the production assembly over one book, which feeds
     * the writers and is the bundle's record alike (design D12 of tighten-spec-contract).
     */
    private static TaskGit build(GitProcessRunner runner, ClaimEpochBook epochs, InstantSource clock = new VirtualClock()) {
        // The base-ref capability is real too (FR5, FR6 of add-base-ref-resolution): serve/take
        // startup reads origin's default branch through it. Its retry runs on virtual time, so a
        // dead origin in a spec exhausts the production bound instantly instead of sleeping.
        GitTaskBackend.taskGit(runner, VirtualTimeGitRetries.gitInfrastructure(), clock,
                UnaryOperator.identity(), epochs)
    }
}
