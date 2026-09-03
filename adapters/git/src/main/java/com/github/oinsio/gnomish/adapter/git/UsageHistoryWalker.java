package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.git.state.StateAttemptDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.StatePositionDto;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException;
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult;
import com.github.oinsio.gnomish.app.port.git.UsageRow;
import com.github.oinsio.gnomish.app.port.git.UsageTotals;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconstructs {@code gnomish usage}'s per-round history by walking {@code state.json} itself,
 * never commit messages (design D14, FR14, NFR-C1): a chronological ({@code git log --reverse})
 * walk of every commit on the task branch touching {@code .gnomish-task/state.json}, diffing each
 * commit's parsed state against the previous one it read. A commit yields a {@link UsageRow} when
 * its {@code attempts} list carries an {@link StateAttemptDto} the previous state didn't have —
 * either the list simply grew (same stage, one more round appended) or the position changed to a
 * new stage visit, whose {@code attempts} list starts over at a single fresh round ({@link
 * com.github.oinsio.gnomish.domain.engine.TaskState#advanceTo} resets history to empty before the
 * new stage's first round is recorded, so the new state's one-element {@code attempts} list is
 * itself the new round). A salvage commit ({@link WorktreeSalvage}) never touches {@code
 * state.json} at all (it is not routed through {@code AttemptPersistence#persist}), so it is
 * filtered out at the {@code git log} level (the path restriction) before any diffing happens —
 * same as a {@code task.json}-only lifecycle commit (create, resume, outcome), which touches only
 * {@code task.json}. The one exception is the final {@code Completed} cleanup commit ({@code
 * GitTaskRepository}, FR15): it deletes {@code state.json}, so a path-filtered {@code git log}
 * still reports it, but there is nothing to read at that tree — handled explicitly by treating a
 * missing blob as "no state here", never a diff. No commit-message parsing is used anywhere in
 * this class.
 *
 * <p>Branch lookup is delegated verbatim to {@link TaskBranchLocator} (task 2.6), the same
 * read-only, no-worktree, no-checkout idiom {@link BranchStateReader} and {@link TaskBranchLister}
 * already rely on (design D13).
 *
 * <p>A historical commit whose {@code state.json} cannot be read — an unsupported envelope version,
 * a half-written or hand-edited document — is skipped with a warning naming the commit, and the
 * walk continues (FR16 of harden-task-branch-contract): a broken commit in the middle of history
 * costs its own row, never the whole report. The next readable commit is diffed against the last
 * readable one, so the rounds recorded across the gap are still attributed, and only the rounds the
 * unreadable commit itself would have contributed are lost.
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow; FR16 of harden-task-branch-contract.
 */
public final class UsageHistoryWalker {

    private static final Logger log = LoggerFactory.getLogger(UsageHistoryWalker.class);

    private static final String STATE_JSON_PATH = GnomishTaskPaths.STATE_JSON_PATH;

    private final GitProcessRunner runner;
    private final TaskBranchLocator locator;

    public UsageHistoryWalker(GitProcessRunner runner) {
        this.runner = runner;
        this.locator = new TaskBranchLocator(runner);
    }

    /**
     * Locates the task branch for {@code taskId} in the clone at {@code cloneDir} and walks its
     * {@code state.json} history into usage rows.
     *
     * @param cloneDir the working directory of an existing git clone (the {@code --dir} target)
     * @param taskId the tracker's original taskId
     * @return {@link UsageHistoryResult.Found} with every detected round and its totals, or
     *     {@link UsageHistoryResult.NotFound} when no branch exists anywhere for this task
     */
    public UsageHistoryResult walk(Path cloneDir, String taskId) {
        BranchLocation location = locator.locate(cloneDir, taskId);
        String ref =
                switch (location) {
                    case BranchLocation.NotFound ignored -> null;
                    case BranchLocation.Unavailable(String reason) ->
                        throw new BranchLocationUnavailableException(taskId, reason);
                    case BranchLocation.Local local -> local.ref();
                    case BranchLocation.RemoteTracking tracking -> tracking.ref();
                };
        if (ref == null) {
            return new UsageHistoryResult.NotFound();
        }

        List<UsageRow> rows = walkRows(cloneDir, ref);
        return new UsageHistoryResult.Found(rows, UsageTotals.of(rows));
    }

    private List<UsageRow> walkRows(Path cloneDir, String ref) {
        List<UsageRow> rows = new ArrayList<>();
        StateJsonDto previous = null;
        for (String commit : stateTouchingCommits(cloneDir, ref)) {
            StateJsonDto current = readStateAt(cloneDir, commit);
            if (current == null) {
                // Two cases, both contributing no row: the cleanup commit (FR15) removes
                // .gnomish-task/ entirely, so it is itself a "commit touching state.json" (a
                // deletion) that git log's pathspec still reports; and an unreadable state file,
                // already warned about, which the walk steps over rather than failing on (FR16).
                continue;
            }
            UsageRow row = detectNewRound(previous, current);
            if (row != null) {
                rows.add(row);
            }
            previous = current;
        }
        return rows;
    }

    /**
     * Every commit reachable from {@code ref} that touches {@code state.json}, oldest first
     * ({@code --reverse}) — the path restriction alone already excludes {@code task.json}-only
     * lifecycle commits and any commit that never wrote {@code state.json} (salvage). The final
     * {@code Completed} cleanup commit (FR15) DOES appear here (git log reports deletions of a
     * pathspec too), handled by {@link #readStateAt} returning {@code null} for it.
     */
    private List<String> stateTouchingCommits(Path cloneDir, String ref) {
        GitCommandResult log = answered(
                ref, "log", runner.run(cloneDir, "log", "--reverse", "--format=%H", ref, "--", STATE_JSON_PATH));
        if (log.exitCode() != 0) {
            // The whole branch's usage report silently becomes empty otherwise (FR5).
            // throwable-not-subject: git reported a status, not a thrown fault.
            UsageHistoryWalker.log.warn(
                    OperatorEvent.USAGE_HISTORY_LISTING_FAILED.head()
                            + "usage: could not list the state-touching commits of {} (git exited {}); the report will be"
                            + " empty: {}",
                    ref,
                    log.exitCode(),
                    LogText.forLog(log.stderr()));
            return List.of();
        }
        return log.stdout().lines().filter(UsageHistoryWalker::isNonBlank).toList();
    }

    /**
     * The gate both history reads pass through, same rule as {@link GitShowTip}: a result is a fact
     * about the branch only when the invocation ran to its own exit. An interrupted {@code git log}
     * hands back a prefix of the commit list — a report silently missing its newest rounds — and an
     * interrupted {@code git show} reads as the absent-state case, silently dropping a commit's
     * rounds; both must surface as unavailability instead.
     */
    private static GitCommandResult answered(String revision, String command, GitCommandResult result) {
        return switch (result.termination()) {
            case EXITED -> result;
            case TIMED_OUT, INTERRUPTED ->
                throw new BranchTipUnavailableException(
                        revision, command, result.termination().name());
        };
    }

    /**
     * {@code @DoNotMutate}: a real {@code git log --format=%H} never emits a blank line, so this
     * defensive filter's own true/false branches are behaviorally unobservable through {@link
     * #walk} — a blank "commit hash" that slipped past it would still fail {@link #readStateAt}
     * with a non-zero {@code git show} exit (proven: any blank/malformed revision spec is rejected
     * by git itself), so it contributes no row either way, mutated or not. Kept as a defense against
     * a hypothetical future git behavior change, not something a unit test can distinguish from its
     * own mutant without faking git's output (see {@code UsageHistoryWalkerSpec}'s fake-git-binary
     * scenario, which documents this same non-observability directly).
     */
    @DoNotMutate
    private static boolean isNonBlank(String line) {
        return !line.isBlank();
    }

    /**
     * Reads {@code state.json} at {@code commit}, or {@code null} when this commit contributes no
     * state: the file is absent at its tree (the cleanup commit, FR15 — the one case where a
     * path-filtered {@code git log} entry has nothing to show), or it is present but unreadable, in
     * which case the commit is named in a warning and skipped rather than failing the walk (FR16 of
     * harden-task-branch-contract).
     */
    private @Nullable StateJsonDto readStateAt(Path cloneDir, String commit) {
        GitCommandResult show = answered(commit, "show", runner.run(cloneDir, "show", commit + ":" + STATE_JSON_PATH));
        if (show.exitCode() != 0) {
            // DEBUG, not WARN: this IS the normal outcome being classified — the cleanup commit
            // (FR15) deletes state.json, so a path-filtered log entry with nothing to show is
            // expected on every completed branch (`.claude/rules/logging.md`).
            // throwable-not-subject: the absence is the classification; git threw nothing.
            log.debug("usage: commit {} carries no {} (the cleanup commit reads this way)", commit, STATE_JSON_PATH);
            return null;
        }
        try {
            return StateJsonMapper.readDto(show.stdout());
        } catch (RuntimeException failure) {
            log.warn(
                    OperatorEvent.USAGE_HISTORY_COMMIT_UNREADABLE.head()
                            + "usage: skipping commit {} — its {} could not be read",
                    commit,
                    STATE_JSON_PATH,
                    failure);
            return null;
        }
    }

    /**
     * The heart of D14's algorithm: {@code current}'s new round, if any, relative to {@code
     * previous} — the previous state read on this walk, or {@code null} for the very first
     * state.json commit encountered.
     *
     * <p>A round is new when {@code current}'s newest recorded attempt is not the one {@code
     * previous} ended with; the same newest attempt means this commit recorded no round (a
     * decision's attempt-counter reset, say, which rewrites the file without running anything).
     *
     * <p>Which stage the round is billed to is decided by the list, not by the position. A list
     * that grew by one is another round of the stage {@code previous} was at — and that includes
     * the PASSING round, whose commit already carries the advanced position (FR4 of
     * harden-task-branch-contract). A list that reset instead holds the first round of the stage
     * {@code current} names.
     */
    private @Nullable UsageRow detectNewRound(@Nullable StateJsonDto previous, StateJsonDto current) {
        List<StateAttemptDto> attempts = current.attempts();
        if (attempts.isEmpty()) {
            return null;
        }
        StateAttemptDto newest = attempts.getLast();
        if (previous == null) {
            return new UsageRow(stageName(current.position()), StateJsonMapper.fromAttempt(newest));
        }
        if (newest.equals(newestOf(previous))) {
            return null;
        }
        StatePositionDto ranAt = grewFrom(previous, current) ? previous.position() : current.position();
        return new UsageRow(stageName(ranAt), StateJsonMapper.fromAttempt(newest));
    }

    /** The last attempt {@code state} records, or {@code null} when it records none. */
    private static @Nullable StateAttemptDto newestOf(StateJsonDto state) {
        List<StateAttemptDto> attempts = state.attempts();
        return attempts.isEmpty() ? null : attempts.getLast();
    }

    /**
     * Whether {@code current}'s attempt list is {@code previous}' list with one round appended —
     * the shape of "another round of the stage {@code previous} was at", including the passing
     * round whose commit also advanced the position (FR4 of harden-task-branch-contract). A list
     * that instead RESET — the next stage's first round, recorded after a list of one or more —
     * does not grow by one, so it fails this test and its round is attributed to the position it
     * is recorded at.
     */
    private static boolean grewFrom(StateJsonDto previous, StateJsonDto current) {
        return current.attempts().size() == previous.attempts().size() + 1;
    }

    private static String stageName(StatePositionDto position) {
        return switch (position) {
            case StatePositionDto.AtStage atStage -> atStage.stage();
            case StatePositionDto.PipelineEnd ignored -> "(pipeline end)";
        };
    }
}
