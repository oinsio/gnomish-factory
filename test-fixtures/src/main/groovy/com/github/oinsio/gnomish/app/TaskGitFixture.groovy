package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.GitBaseRefs
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches
import com.github.oinsio.gnomish.adapter.git.GitTaskStore
import com.github.oinsio.gnomish.adapter.git.GitTaskWorktrees
import com.github.oinsio.gnomish.adapter.git.VirtualTimeGitRetries
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
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
        def epochs = new ClaimEpochBook()
        build(epochs, epochs)
    }

    /**
     * A {@link TaskGit} whose writers hold no claim tenure at all: the bundle still carries a
     * tenure record, because no {@link TaskGit} may be built without one (FR4 of
     * fix-claim-epoch-fence), but its writers are wired to {@link ClaimEpochSource#NONE} rather
     * than to it. For the commands that never claim — {@code status}, {@code usage}, {@code board}
     * — where the distinction is the point of the spec; every other spec wants {@link #real()}.
     */
    static TaskGit realClaimless() {
        build(ClaimEpochSource.NONE, new ClaimEpochBook())
    }

    /** The one construction both forms share: {@code stamps} feeds the writers, {@code record} the bundle. */
    private static TaskGit build(ClaimEpochSource stamps, ClaimEpochBook record) {
        def runner = new GitProcessRunner()
        // The base-ref capability is real too (FR5, FR6 of add-base-ref-resolution): serve/take
        // startup reads origin's default branch through it. Its retry runs on virtual time, so a
        // dead origin in a spec exhausts the production bound instantly instead of sleeping.
        new TaskGit(new GitTaskStore(runner, stamps), new GitTaskBranches(runner, stamps),
                new GitTaskWorktrees(runner, stamps), UnaryOperator.identity(),
                new GitBaseRefs(runner, VirtualTimeGitRetries.gitInfrastructure()), record)
    }
}
