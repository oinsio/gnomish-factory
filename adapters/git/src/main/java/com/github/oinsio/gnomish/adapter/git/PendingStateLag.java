package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The host round close's reader of the lag a pending commit leaves behind (design D21 of
 * migrate-verify-to-executor-contract, "The lag the write leaves behind"): the pending commit moves
 * the branch, and with it the worktree's {@code HEAD}, while the index and the working tree keep the
 * {@code state.json} they had. Unrecognised, that difference would read as the gnome's uncommitted
 * envelope edit at the round close ({@link EnvelopeRestore#uncommittedEdits}).
 *
 * <p>Recognised by identity, never by the path alone: only when the index's and the working tree's
 * {@code state.json} are one object, and that object is the {@code state.json} of one of the round's
 * own registered commits is that one path restored from the register before the judgement. Any
 * other content is left for the judgement, as before. Nothing else writes in the worktree at that
 * point: every check of the round has ended.
 *
 * <p>The round's registered commits are its {@linkplain LastRegisteredCommit#registeredChain()
 * registered chain} as the run's round cell holds it — the live writes' own record, or the chain the
 * resume's one walk passed (design D14 as amended 2026-11-13), which includes a pending commit a
 * resumed worktree was reset to. Nothing here walks it again (crash-consistency item 14).
 *
 * <p>Every read that does not answer leaves the path alone, so the judgement after it decides — a
 * lag that cannot be recognised is refused there, never assumed.
 *
 * <p>Implements FR20 of migrate-verify-to-executor-contract.
 */
final class PendingStateLag {

    private static final String STATE = EnvelopePaths.STATE_JSON_PATH;

    private final GitRunner runner;
    private final Path worktree;
    private final String taskId;

    PendingStateLag(GitRunner runner, Path worktree, String taskId) {
        this.runner = runner;
        this.worktree = worktree;
        this.taskId = taskId;
    }

    /**
     * Restores {@code state.json} from the register when the worktree lags a pending commit of the
     * round whose registered chain is {@code chain}.
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
        Optional<String> index =
                VerifiedTip.read(runner.run(worktree, "rev-parse", "--verify", "--quiet", ":" + STATE));
        Optional<String> tree = VerifiedTip.read(runner.run(worktree, "hash-object", "--no-filters", "--", STATE));
        if (index.isEmpty() || !index.equals(tree) || !chainCarries(chain, index.get())) {
            return Optional.empty();
        }
        String source = "--source=" + FactoryRegisterRefs.of(taskId).name();
        GitCommandResult restore = runner.run(worktree, "restore", source, "--staged", "--worktree", "--", STATE);
        return restore.succeeded()
                ? Optional.empty()
                : Optional.of(restore.failureDetail("restore of the pending commit's state.json"));
    }

    private boolean chainCarries(List<String> chain, String blob) {
        for (String commit : chain) {
            Optional<String> carried =
                    VerifiedTip.read(runner.run(worktree, "rev-parse", "--verify", "--quiet", commit + ":" + STATE));
            if (carried.isPresent() && carried.get().equals(blob)) {
                return true;
            }
        }
        return false;
    }
}
