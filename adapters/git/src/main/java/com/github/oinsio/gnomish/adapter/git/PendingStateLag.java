package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.ClosedRound;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
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
 * own registered commits — the first-parent chain from {@link ClosedRound#registered()} down to
 * {@link ClosedRound#attemptCommit()}, which includes a pending commit a resumed worktree was reset
 * to — is that one path restored from the register before the judgement. Any other content is left
 * for the judgement, as before. Nothing else writes in the worktree at that point: every check of the
 * round has ended.
 *
 * <p>Every read that does not answer leaves the path alone, so the judgement after it decides — a
 * lag that cannot be recognised is refused there, never assumed.
 *
 * <p>Implements FR20 of migrate-verify-to-executor-contract.
 */
final class PendingStateLag {

    /** More pending commits than any round lands; a longer chain is not the round's. */
    static final int CHAIN_BOUND = 256;

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
     * Restores {@code state.json} from the register when the worktree lags a pending commit of
     * {@code round}.
     *
     * @return empty when nothing was owed or the restore succeeded; the failed restore's detail
     *     otherwise
     */
    Optional<UntrustedText> settle(ClosedRound round) {
        if (round.registered().equals(round.attemptCommit())) {
            return Optional.empty();
        }
        Optional<String> index =
                VerifiedTip.read(runner.run(worktree, "rev-parse", "--verify", "--quiet", ":" + STATE));
        Optional<String> tree = VerifiedTip.read(runner.run(worktree, "hash-object", "--no-filters", "--", STATE));
        if (index.isEmpty() || !index.equals(tree) || !chainCarries(round, index.get())) {
            return Optional.empty();
        }
        String source = "--source=" + FactoryRegisterRefs.of(taskId).name();
        GitCommandResult restore = runner.run(worktree, "restore", source, "--staged", "--worktree", "--", STATE);
        return restore.succeeded()
                ? Optional.empty()
                : Optional.of(restore.failureDetail("restore of the pending commit's state.json"));
    }

    private boolean chainCarries(ClosedRound round, String blob) {
        String commit = round.registered();
        for (int step = 0; step < CHAIN_BOUND; step++) {
            Optional<String> carried =
                    VerifiedTip.read(runner.run(worktree, "rev-parse", "--verify", "--quiet", commit + ":" + STATE));
            if (carried.isPresent() && carried.get().equals(blob)) {
                return true;
            }
            if (commit.equals(round.attemptCommit())) {
                return false;
            }
            Optional<String> parent =
                    VerifiedTip.read(runner.run(worktree, "rev-parse", "--verify", "--quiet", commit + "^1"));
            if (parent.isEmpty()) {
                return false;
            }
            commit = parent.get();
        }
        return false;
    }
}
