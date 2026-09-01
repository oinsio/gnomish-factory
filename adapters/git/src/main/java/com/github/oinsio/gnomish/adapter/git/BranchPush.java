package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.logtext.LogText;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort push of the task branch to {@code origin} outside a round boundary — currently
 * used only by revocation's salvage protocol (FR15, D2 of add-tracker-port), after {@link
 * WorktreeSalvage#salvage} has already made the interrupted work durable locally. Unlike {@link
 * BestEffortPush}, which gates a round commit's push on freshly reconfirmed round-boundary
 * preconditions (still on the task branch, previous tip still an ancestor of {@code HEAD}), this
 * class has no "previous round tip" to reconfirm: revocation is not a round, so there is nothing
 * for {@link RoundBoundaryCheck} to check here. This class is a distinct, simpler sibling rather
 * than a reuse of {@link BestEffortPush}, kept in its own file — one file, one thing.
 *
 * <p>Mirrors {@link BestEffortPush}'s exact refspec convention and failure discipline: the push
 * uses {@code origin branch:branch}, never a bare branch name; {@code --force} /
 * {@code --force-with-lease} are never used, so a non-fast-forward rejection is just another
 * failed push; with no {@code origin} remote configured the run is purely local and this is a
 * silent no-op; with {@code origin} configured, a failed push logs one WARN and returns normally
 * — it never throws and never retries, since a push is never load-bearing for correctness once
 * the salvage commit already made the work durable locally.
 *
 * <p>Like its siblings, it names a killed or interrupted push for what it is through {@link
 * PushOutcome} instead of calling it a failure (FR8 of bound-subprocess-commands).
 *
 * <p>Implements FR15, D2 of add-tracker-port; FR8, NFR-O2, UX3 of bound-subprocess-commands.
 */
// Not a record: this is a behavior-bearing push service (a collaborator, not immutable data),
// kept as a plain final class for parity with its documented sibling BestEffortPush.
public final class BranchPush {

    private static final Logger log = LoggerFactory.getLogger(BranchPush.class);

    private final OriginRemote origin;
    private final RefspecPush push;

    public BranchPush(GitProcessRunner runner) {
        this.origin = new OriginRemote(runner);
        this.push = new RefspecPush(runner);
    }

    /**
     * Pushes {@code branch} to {@code origin} in {@code worktreeRoot} using the exact refspec
     * {@code branch:branch}, if {@code origin} is configured. Otherwise, does nothing at all —
     * silent, no-origin no-op, mirroring {@link BestEffortPush}'s FR11 "no warnings" rule. Never
     * throws, never retries, never forces.
     *
     * @param worktreeRoot the task worktree; git commands run with this path as {@code cwd}
     * @param branch the task branch name to push, e.g. {@link TaskIdSanitizer#branchName}
     */
    public void pushBestEffort(Path worktreeRoot, String branch) {
        if (!origin.isConfigured(worktreeRoot)) {
            return;
        }

        GitCommandResult result = push.push(worktreeRoot, branch);
        String outcome = PushOutcome.describe("revocation push", result);
        if (outcome != null) {
            log.warn("{}: branch={}, stderr={}", outcome, branch, LogText.forLog(result.stderr()));
        }
    }
}
