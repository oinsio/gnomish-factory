package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.PendingSubject;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Optional;

/**
 * The one reader of the lag a pending commit leaves behind (design D21 of
 * migrate-verify-to-executor-contract, "The lag the write leaves behind"; design D11 of
 * extract-agent-round-runner, as amended 2026-11-25): the pending commit moves the branch, and with it
 * the working copy's {@code HEAD}, while the index and the working tree keep the {@code state.json}
 * they had. Unrecognised, that difference would read as the gnome's uncommitted envelope edit at the
 * host round close ({@link EnvelopeRestore#uncommittedEdits}), and would be staged into the closing
 * snapshot of a pending round on either medium — the box's admission then refuses it as a change
 * under the state directory. Its callers: the host round close ({@link GitAttemptPersistence}) and the
 * one snapshot step ({@link RoundSnapshot}) on both media, each through the {@link WorkingCopyGit} of
 * its working copy, so the rule has one implementation.
 *
 * <p>Recognised by identity, never by the path alone: only when the index's and the working tree's
 * {@code state.json} are one object, and that object is the {@code state.json} of one of the round's
 * own registered commits is that one path restored from the round's last registered commit — the
 * commit the register names — before the judgement and the staging. Any other content is left for
 * the judgement, as before. Nothing else writes in the working copy at any of its points: at the
 * round close every check of the round has ended, at the snapshot none has started, and after a
 * pending round commit landed ({@link #afterLanding}) nothing of the round runs there.
 *
 * <p>After the landing (design D11 of extract-agent-round-runner, as amended 2026-11-25, finding F1
 * of task 6.4d): a pending round commit is written past the working copy — from objects on the host
 * ({@link HostPendingCommit}), through a private index in the box ({@link InBoxPendingCommit}) — so
 * the working copy's {@code HEAD} moves and its {@code state.json} stays the older blob. An executor
 * that commits in the working copy during a poll ({@code git add -A && git commit}) would carry that
 * older blob into its commit, and the round's range judgement refuses a gnome commit that changes
 * {@code state.json}. Both persistence ends therefore settle the lag right after a pending commit of
 * the round's own entry landed with its register. Nothing of the round runs in the working copy
 * then: the {@code start} that answered {@code pending} has returned, and the pending-round snapshot
 * already judged and staged the working copy whole, so the restore overwrites only the factory's own
 * lag. A kill between the landing and the settle is harmless: a resumed poll runs in an environment
 * at the registered commit (D11). A pending commit of a check's entry is never followed by a settle:
 * the check's run is still alive in the working copy, which is why its commit is built past it.
 *
 * <p>The round's registered commits are its {@linkplain LastRegisteredCommit#registeredChain()
 * registered chain} as the run's round cell holds it — the live writes' own record, or the chain the
 * resume's one walk passed (design D14 as amended 2026-11-13), which includes a pending commit a
 * resumed worktree was reset to. Nothing here walks it again (crash-consistency item 14).
 *
 * <p>Every read that does not answer leaves the path alone, so the judgement after it decides — a
 * lag that cannot be recognised is refused there, never assumed.
 *
 * <p>Implements FR20 of migrate-verify-to-executor-contract; FR10 of extract-agent-round-runner.
 */
final class PendingStateLag {

    private static final String STATE = EnvelopePaths.STATE_JSON_PATH;

    private final WorkingCopyGit git;

    /** @param git git in the round's working copy; never null */
    PendingStateLag(WorkingCopyGit git) {
        this.git = git;
    }

    /**
     * Settles the lag a pending commit that just landed left, when the commit is the round's own
     * (a pending round commit); a check's pending commit leaves the working copy untouched.
     *
     * @param write the pending write that landed
     * @param chain the round's registered chain, the landed commit at its head
     * @throws GitPersistFailedException if a recognised lag could not be restored
     */
    void afterLanding(PendingWrite write, List<String> chain) {
        if (write.last().subject() instanceof PendingSubject.Round) {
            settled(write.key(), chain);
        }
    }

    /**
     * {@link #settle}, with a failed restore thrown as the round's infrastructure failure.
     *
     * @param key the round whose working copy is settled
     * @param chain the round's registered chain
     * @throws GitPersistFailedException if a recognised lag could not be restored
     */
    void settled(AttemptKey key, List<String> chain) {
        settle(chain).ifPresent(failure -> {
            throw new GitPersistFailedException(
                    key.taskId(), key.stage(), key.attempt(), "pending-commit lag", failure);
        });
    }

    /**
     * Restores {@code state.json} from the round's last registered commit when the working copy lags
     * a pending commit of the round whose registered chain is {@code chain}.
     *
     * @param chain the round's registered chain: the last registered commit first, the snapshot last;
     *     a chain of one (the snapshot) or none means no pending commit, so nothing is owed
     * @return empty when nothing was owed or the restore succeeded; the failed restore's detail
     *     otherwise
     */
    Optional<UntrustedText> settle(List<String> chain) {
        if (chain.size() < 2) {
            return Optional.empty();
        }
        Optional<String> index = VerifiedTip.read(git.run("rev-parse", "--verify", "--quiet", ":" + STATE));
        Optional<String> tree = VerifiedTip.read(git.run("hash-object", "--no-filters", "--", STATE));
        if (index.isEmpty() || !index.equals(tree) || !chainCarries(chain, index.get())) {
            return Optional.empty();
        }
        // The chain's head is the commit the register names, as the cell recorded it: no ref is read,
        // and a box, which holds no register ref, restores from the same value (crash-consistency 14).
        String source = "--source=" + chain.getFirst();
        GitCommandResult restore = git.run("restore", source, "--staged", "--worktree", "--", STATE);
        return restore.succeeded()
                ? Optional.empty()
                : Optional.of(restore.failureDetail("restore of the pending commit's state.json"));
    }

    private boolean chainCarries(List<String> chain, String blob) {
        for (String commit : chain) {
            Optional<String> carried =
                    VerifiedTip.read(git.run("rev-parse", "--verify", "--quiet", commit + ":" + STATE));
            if (carried.isPresent() && carried.get().equals(blob)) {
                return true;
            }
        }
        return false;
    }
}
