package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Optional;

/**
 * Where a round's snapshot is committed — the medium strategy of the one {@link RoundSnapshot} step
 * (design D22 of migrate-verify-to-executor-contract; the {@code ResumeMechanics<B>} shape): the box,
 * through an in-box commit, the harvest and the admission ({@link InBoxSnapshot}), or the host
 * worktree, through its index and a closing commit ({@link WorktreeSnapshot}). The step owns the
 * subject, the order — the pending lag settled, the verdict, the commit — and the round cell's
 * transition; a medium only settles, judges what it can see before it commits, and commits.
 *
 * <p>Implements FR19 of migrate-verify-to-executor-contract.
 */
interface SnapshotMedium {

    /**
     * Settles the {@code state.json} lag the round's pending round commits left in this medium's
     * working copy, before anything is judged or staged ({@link PendingStateLag}; design D11 of
     * extract-agent-round-runner, as amended 2026-11-25).
     *
     * @param order the round's snapshot order; its chain names the round's registered commits
     * @return empty when nothing was owed or the restore succeeded; the failed restore's detail otherwise
     */
    Optional<UntrustedText> settle(SnapshotOrder order);

    /**
     * The round's boundary as this medium can judge it before anything is committed. Clean lets the
     * step commit; a violation or cannot-verify declines the snapshot, and the round's persist judges
     * the same working copy itself.
     *
     * @param order the round's snapshot order; never null
     * @return the verdict; never null
     */
    RangeVerdict judge(SnapshotOrder order);

    /**
     * Commits the round's whole working copy under {@code order.subject()} — a commit even when the
     * round changed nothing — and lands it with the task's register in one ref transaction.
     *
     * @param order the round's snapshot order; never null
     * @return the snapshot commit id; never blank
     * @throws GitPersistFailedException if the commit could not be made
     */
    String commit(SnapshotOrder order);
}
