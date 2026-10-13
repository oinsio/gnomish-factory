package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.gittransfer.QuarantineRef;
import com.github.oinsio.gnomish.gittransfer.Refspec;
import com.github.oinsio.gnomish.gittransfer.RegisterRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The factory register of a task in the factory clone (design D13 of tighten-spec-contract), and
 * the one owner of every move of the task branch and its register there: which ref names the
 * register, and the ref transactions that move the two. The register names the factory's latest
 * closing commit; the closing push ({@link ClosingPush}) carries it to origin in the same atomic
 * push as the branch, and a touchpoint fetch brings origin's back (the refspecs below). A reader of
 * the registered commit names the ref through {@link #of} too, so the writers and every reader
 * agree on one spelling ({@link RegisterRef} holds the namespace).
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

    private static final String HEADS = "refs/heads/";

    private final GitRunner runner;

    FactoryRegister(GitRunner runner) {
        this.runner = runner;
    }

    /**
     * The register of the task {@code taskId}: {@code refs/gnomish-factory/<sanitized taskId>}, the
     * same core string the branch {@code gnomish/<sanitized taskId>} carries.
     *
     * @param taskId the tracker's original taskId
     * @return the register
     */
    static RegisterRef of(String taskId) {
        return RegisterRef.forTask(TaskIdSanitizer.sanitize(taskId));
    }

    /**
     * The harvest quarantine of the task {@code taskId} (design D18): {@code
     * refs/gnomish-quarantine/<sanitized taskId>}, where a harvest lands the box's branch and the
     * admission ({@link HarvestAdmission}) reads it from.
     *
     * @param taskId the tracker's original taskId, or the core string a branch name carries
     * @return the quarantine
     */
    static QuarantineRef quarantine(String taskId) {
        return QuarantineRef.forTask(TaskIdSanitizer.sanitize(taskId));
    }

    /**
     * The refspec a touchpoint fetch that has no local branch to protect lands origin's register
     * with, straight into the clone's register: {@code +<register>:<register>}.
     */
    static Refspec fetchedInPlace(String taskId) {
        String name = of(taskId).name();
        return new Refspec("+" + name + ":" + name);
    }

    /**
     * The refspec a touchpoint fetch beside a local branch lands origin's register with: its
     * remote-tracking name, so a clone register ahead of origin's (a closing push origin has not
     * received yet) is never rolled back by a fetch.
     */
    static Refspec fetchedTracking(String taskId) {
        RegisterRef register = of(taskId);
        return new Refspec("+" + register.name() + ":" + register.trackingName());
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
                        new RefTransaction.Update(branchRef(taskId), commit, expectedTip),
                        new RefTransaction.Update(of(taskId).name(), commit, null))));
    }

    /**
     * Moves the task branch alone from {@code expectedTip} to {@code commit} — an admitted gnome
     * range, which is no closing commit, so the register stays where it is.
     *
     * @return the transaction's result; a non-zero exit left the branch where it was
     */
    GitCommandResult advance(Path repo, String taskId, String commit, String expectedTip) {
        return runner.run(
                repo, new RefTransaction(List.of(new RefTransaction.Update(branchRef(taskId), commit, expectedTip))));
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
            updates.add(new RefTransaction.Update(of(taskId).name(), originRegister, null));
        } else if (offLineRegister != null) {
            updates.add(RefTransaction.Update.deletion(of(taskId).name(), offLineRegister));
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
                        new RefTransaction.Update(of(taskId).name(), originRegister, expectedRegister))));
    }

    /**
     * Origin's register as the last touchpoint fetch landed it, or {@code null} when origin carries
     * none. A read cut off before its own exit refuses ({@link GitReadGate}) rather than answering
     * "none", which would move the branch without the register origin holds beside it.
     */
    private @Nullable String originRegister(Path repo, String taskId) {
        String tracking = of(taskId).trackingName();
        return VerifiedTip.read(GitReadGate.answered(
                        tracking, "rev-parse", runner.run(repo, "rev-parse", "--verify", "--quiet", tracking)))
                .orElse(null);
    }

    /**
     * The full ref of {@code taskId}'s task branch, {@code refs/heads/gnomish/<sanitized taskId>} —
     * the one spelling the register's writers and the admission read move together.
     *
     * @param taskId the tracker's original taskId, or the core string a branch name carries
     * @return the branch's full ref name
     */
    static String branchRef(String taskId) {
        return HEADS + TaskIdSanitizer.branchName(taskId);
    }
}
