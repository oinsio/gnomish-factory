package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.git.state.StateAttemptDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.StatePositionDto;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 */
public final class UsageHistoryWalker {

    private static final String STATE_JSON_PATH = ".gnomish-task/state.json";

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
     * @throws com.github.oinsio.gnomish.adapter.git.state.UnsupportedStateFileVersionException if
     *     any visited {@code state.json} carries an unsupported {@code "version"}
     */
    public UsageHistoryResult walk(Path cloneDir, String taskId) {
        BranchLocation location = locator.locate(cloneDir, taskId);
        String ref =
                switch (location) {
                    case BranchLocation.NotFound ignored -> null;
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
                // The cleanup commit (FR15) removes .gnomish-task/ entirely, so it is itself a
                // "commit touching state.json" (a deletion) that git log's pathspec still reports
                // — but there is no state to diff against here, so it contributes no row.
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
        GitCommandResult log = runner.run(cloneDir, "log", "--reverse", "--format=%H", ref, "--", STATE_JSON_PATH);
        if (log.exitCode() != 0) {
            return List.of();
        }
        return log.stdout().lines().filter(UsageHistoryWalker::isNonBlank).toList();
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
     * Reads {@code state.json} at {@code commit}, or {@code null} if it is absent at that commit
     * — the cleanup commit's tree (FR15), the one case where a path-filtered {@code git log} entry
     * has nothing to show.
     */
    private @Nullable StateJsonDto readStateAt(Path cloneDir, String commit) {
        GitCommandResult show = runner.run(cloneDir, "show", commit + ":" + STATE_JSON_PATH);
        if (show.exitCode() != 0) {
            return null;
        }
        return StateJsonMapper.readDto(show.stdout());
    }

    /**
     * The heart of D14's algorithm: {@code current}'s new round, if any, relative to {@code
     * previous} — the previous state read on this walk, or {@code null} for the very first
     * state.json commit encountered (whose whole {@code attempts} list is new by definition; every
     * production state.json carries at most one attempt at its very first commit, per {@link
     * com.github.oinsio.gnomish.domain.engine.TaskState#atStageStart}).
     *
     * <p>Same stage as {@code previous} (list grew by exactly one): the newly appended tail
     * element is the round. Different stage (advancement reset the list): the new state's own
     * {@code attempts} list holds exactly the fresh stage's first recorded round. Either way the
     * new round is simply {@code current.attempts()}' last element, once {@code current} carries
     * more information than {@code previous} for its stage.
     */
    private @Nullable UsageRow detectNewRound(@Nullable StateJsonDto previous, StateJsonDto current) {
        List<StateAttemptDto> attempts = current.attempts();
        if (attempts.isEmpty()) {
            return null;
        }
        if (previous != null
                && samePosition(previous.position(), current.position())
                && attempts.size() <= previous.attempts().size()) {
            return null;
        }
        StateAttemptDto newest = attempts.get(attempts.size() - 1);
        return new UsageRow(stageName(current.position()), newest);
    }

    private static boolean samePosition(StatePositionDto left, StatePositionDto right) {
        return switch (left) {
            case StatePositionDto.AtStage a
            when right instanceof StatePositionDto.AtStage b -> a.stage().equals(b.stage());
            case StatePositionDto.PipelineEnd ignored -> right instanceof StatePositionDto.PipelineEnd;
            default -> false;
        };
    }

    private static String stageName(StatePositionDto position) {
        return switch (position) {
            case StatePositionDto.AtStage atStage -> atStage.stage();
            case StatePositionDto.PipelineEnd ignored -> "(pipeline end)";
        };
    }
}
