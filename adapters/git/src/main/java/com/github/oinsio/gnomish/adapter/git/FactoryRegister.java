package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The factory register of a task in the factory clone (design D13 of tighten-spec-contract), and
 * the one owner of every move of the task branch and its register there: which ref names the
 * register, and the ref transactions that move the two. The register names the factory's latest
 * closing commit; the closing push ({@link ClosingPush}) carries it to origin in the same atomic
 * push as the branch, and a touchpoint fetch brings origin's back. The ref names — the register's,
 * its fetch refspecs, the harvest quarantine — are spelled by {@link FactoryRegisterRefs}, the task
 * branch's by {@link TaskBranchRefs}; this class holds the transactions over them.
 *
 * <p>Every write is one {@link RefTransaction} — {@code git update-ref --stdin}, the branch under a
 * compare-and-swap against the tip it was read at (amended 2026-10-13):
 *
 * <ul>
 *   <li>{@link #close} — a closing commit: the branch and the register move to it together. A
 *       closing commit is made as an object first ({@code commit-tree}, the bare-object writer, or
 *       the in-box commit a harvest brought), so no kill can leave one above the register — the
 *       window "closing commit landed, register not yet moved" does not exist.
 *   <li>{@link #advance} — the admission of a gnome range mid-round ({@link HarvestAdmission}): the
 *       branch alone, so the register keeps naming the last closing commit.
 *   <li>{@link #adopt} — the replica reconciler's move of the local branch to origin's line
 *       (behind, or a discarded divergence): the register follows origin's, in the same transaction,
 *       when origin carries one; otherwise a clone register off origin's line is deleted with it.
 *   <li>{@link #follow} — the replica reconciler's catch-up of the register alone where the branch
 *       already holds origin's line (equal or ahead): the register moves forward to origin's under
 *       a compare-and-swap against the clone's own, the branch verified unmoved in the same
 *       transaction (amended 2026-10-13, cross-instance resume finding).
 *   <li>{@link #createHead} — the branch adoption's create on absence ({@link
 *       TaskBranchAdoption}; design D9 of fix-terminal-receipt-convergence); an adoption is never
 *       undone (design D12).
 * </ul>
 *
 * <p>The raw result goes back to the caller, which owns what a refused transaction means (a thrown
 * persist, a lifecycle refusal, a lost compare-and-swap to classify again).
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 */
// Not a record: a behavior-bearing writer over the git seam, like its siblings in this package.
@SuppressWarnings("ClassCanBeRecord")
final class FactoryRegister {

    private final GitRunner runner;

    FactoryRegister(GitRunner runner) {
        this.runner = runner;
    }

    /**
     * Lands a closing commit: the task branch moves from {@code expectedTip} to {@code commit}, and
     * the register moves to {@code commit} in the same transaction.
     *
     * @param repo the clone, or a worktree of it; the ref store is shared
     * @param taskId the tracker's original taskId
     * @param commit the closing commit, already an object in the clone
     * @param expectedTip the branch tip the commit was built on
     * @return the transaction's result; a non-zero exit moved neither ref
     */
    GitCommandResult close(Path repo, String taskId, String commit, String expectedTip) {
        return runner.run(
                repo,
                new RefTransaction(List.of(
                        new RefTransaction.Update(TaskBranchRefs.head(taskId), commit, expectedTip),
                        new RefTransaction.Update(FactoryRegisterRefs.of(taskId).name(), commit, null))));
    }

    /**
     * Moves the task branch alone from {@code expectedTip} to {@code commit} — an admitted gnome
     * range, which is no closing commit, so the register stays where it is.
     *
     * @return the transaction's result; a non-zero exit left the branch where it was
     */
    GitCommandResult advance(Path repo, String taskId, String commit, String expectedTip) {
        return runner.run(
                repo,
                new RefTransaction(
                        List.of(new RefTransaction.Update(TaskBranchRefs.head(taskId), commit, expectedTip))));
    }

    /**
     * Moves {@code localRef} from {@code localTip} to origin's {@code remoteTip}; the register as the
     * class javadoc says — a register naming the line given up would read {@code Diverged} forever.
     *
     * @param localTip the local tip the decision was made on, the compare-and-swap's expectation
     * @param offLineRegister the clone's register if not an ancestor of {@code remoteTip}, else null
     * @return the transaction's result; a non-zero exit moved neither ref
     */
    GitCommandResult adopt(
            Path repo,
            String taskId,
            String localRef,
            String remoteTip,
            String localTip,
            @Nullable String offLineRegister) {
        List<RefTransaction.Update> updates = new ArrayList<>();
        updates.add(new RefTransaction.Update(localRef, remoteTip, localTip));
        String originRegister = originRegister(repo, taskId);
        if (originRegister != null) {
            updates.add(new RefTransaction.Update(FactoryRegisterRefs.of(taskId).name(), originRegister, null));
        } else if (offLineRegister != null) {
            updates.add(RefTransaction.Update.deletion(
                    FactoryRegisterRefs.of(taskId).name(), offLineRegister));
        }
        return runner.run(repo, new RefTransaction(updates));
    }

    /**
     * Moves the clone's register from {@code localRegister} to origin's {@code originRegister} while
     * {@code localRef} stays at {@code localTip}, in one transaction: the branch is verified unmoved,
     * the register compared-and-swapped. The caller has decided the move goes forward ({@code
     * localRegister} absent or an ancestor of origin's); this method writes, it does not decide.
     *
     * @param repo the clone, or a worktree of it
     * @param taskId the tracker's original taskId
     * @param localRef the local task branch's full ref name
     * @param localTip the local tip the decision was made on, verified unmoved
     * @param localRegister the clone's register the decision was made on, or {@code null} when the
     *     clone held none — then the register must still not exist
     * @param originRegister origin's register, the register's new value
     * @return the transaction's result; a non-zero exit moved nothing
     */
    GitCommandResult follow(
            Path repo,
            String taskId,
            String localRef,
            String localTip,
            @Nullable String localRegister,
            String originRegister) {
        // update-ref reads an all-zero old value as "must not exist", in the object format's width.
        String expectedRegister = localRegister != null ? localRegister : "0".repeat(localTip.length());
        return runner.run(
                repo,
                new RefTransaction(List.of(
                        new RefTransaction.Update(localRef, localTip, localTip),
                        new RefTransaction.Update(
                                FactoryRegisterRefs.of(taskId).name(), originRegister, expectedRegister))));
    }

    /**
     * Creates the head of {@code taskId}'s branch at {@code tip} if it does not exist — a
     * compare-and-swap on absence, git's {@code create} (design D9 of
     * fix-terminal-receipt-convergence); the register is not touched.
     *
     * @return the transaction's result; a non-zero exit created nothing
     */
    GitCommandResult createHead(Path repo, String taskId, String tip) {
        // update-ref reads an all-zero old value as "must not exist", in the object format's width.
        String absent = "0".repeat(tip.length());
        return runner.run(
                repo, new RefTransaction(List.of(new RefTransaction.Update(TaskBranchRefs.head(taskId), tip, absent))));
    }

    /**
     * Origin's register as the last touchpoint fetch landed it, or {@code null} when origin carries
     * none. A read cut off before its own exit refuses ({@link GitReadGate}) rather than answering
     * "none", which would move the branch without the register origin holds beside it — and so does
     * a zero exit that printed no full object id ({@link VerifiedTip#answered}, FR17 of
     * fix-terminal-receipt-convergence).
     */
    private @Nullable String originRegister(Path repo, String taskId) {
        String tracking = FactoryRegisterRefs.of(taskId).trackingName();
        return VerifiedTip.answered(
                        tracking,
                        "rev-parse",
                        GitReadGate.answered(
                                tracking, "rev-parse", runner.run(repo, "rev-parse", "--verify", "--quiet", tracking)))
                .orElse(null);
    }
}
