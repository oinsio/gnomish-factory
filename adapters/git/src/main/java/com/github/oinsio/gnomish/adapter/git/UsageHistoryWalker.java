package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchLocationRefusedException;
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult;
import com.github.oinsio.gnomish.app.port.git.UsageRow;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconstructs {@code gnomish usage}'s per-round history by walking {@code state.json} itself,
 * never commit messages (design D14 of add-git-workflow; design D13 of define-executor-contract): a
 * chronological walk of the commits on the task branch's <em>first-parent line</em> between the
 * task's recorded {@code baseCommit} (exclusive) and its registered commit that touch {@code
 * .gnomish-task/state.json} — {@code git log --first-parent --reverse <baseCommit>..<register> --
 * state.json}. Each commit's state hands its round records to {@link UsageRoundRows}, which emits a
 * row for every record identity it has not met yet and takes the row's stage and task from the
 * record itself; nothing is inferred from the position or from the shape of the attempt list.
 *
 * <p>The range is the task's own history ({@link TaskHistory}), so a base whose tree carried
 * another task's envelope contributes no commit; the first-parent line drops what a merge of the
 * base into the task branch brought in. A record whose {@code taskId} is not the task's is
 * rejected anyway, with a warning naming the commit: the content check is the second defence.
 *
 * <p>The path restriction excludes {@code task.json}-only lifecycle commits and salvage commits
 * ({@link WorktreeSalvage} never writes {@code state.json}). The {@code Completed} cleanup commit
 * deletes {@code state.json}, so it is listed but reads as "no state here", never a row.
 *
 * <p>A historical commit whose {@code state.json} cannot be read is skipped with a warning naming
 * it, and the walk continues (FR16 of harden-task-branch-contract): a round the next readable
 * commit still lists is still reported, since rows are keyed by record identity.
 *
 * <p>The walk ends at the task's registered commit (design D13 of tighten-spec-contract): a round
 * the gnome wrote above the register is not reported.
 *
 * <p>The walk hands back the rows and the {@code totals} the tip records, and folds nothing: the
 * re-fold of the rows and its comparison with the recorded figure are {@code UsageCommand}'s
 * (design D15, FR18 of define-executor-contract).
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow; FR16 of harden-task-branch-contract; FR13 of
 * tighten-spec-contract; FR14, FR18, FR19 of define-executor-contract.
 */
@UntrustedParser
public final class UsageHistoryWalker {

    private static final Logger log = LoggerFactory.getLogger(UsageHistoryWalker.class);

    private static final String STATE_JSON_PATH = EnvelopePaths.STATE_JSON_PATH;

    private final GitRunner runner;
    private final TaskBranchLocator locator;
    private final FactoryRegisterRead register;

    /**
     * @param runner the git subprocess seam; never null
     * @param retry the infrastructure budget the branch lookup is re-attempted under, the
     *     composition root's own (FR18 of supervise-daemon-loops-and-embed-dashboard); never null
     */
    public UsageHistoryWalker(GitRunner runner, GitInfrastructureRetry retry) {
        this.runner = runner;
        this.locator = new TaskBranchLocator(runner, retry);
        this.register = new FactoryRegisterRead(runner);
    }

    /**
     * Locates the task branch for {@code taskId} in the clone at {@code cloneDir} and walks its
     * {@code state.json} history into usage rows.
     *
     * @param cloneDir the working directory of an existing git clone (the {@code --dir} target)
     * @param taskId the tracker's original taskId
     * @return {@link UsageHistoryResult.Found} with every detected round and the tip's recorded
     *     totals, or
     *     {@link UsageHistoryResult.NotFound} when no branch exists anywhere for this task
     */
    public UsageHistoryResult walk(Path cloneDir, String taskId) {
        BranchLocation location = locator.locate(cloneDir, taskId);
        String ref =
                switch (location) {
                    case BranchLocation.NotFound ignored -> null;
                    case BranchLocation.Unavailable(UntrustedText reason) ->
                        throw new BranchLocationUnavailableException(taskId, reason);
                    case BranchLocation.Refused(UntrustedText report) ->
                        throw new BranchLocationRefusedException(taskId, report);
                    case BranchLocation.Local local -> local.ref();
                    case BranchLocation.RemoteTracking tracking -> tracking.ref();
                };
        if (ref == null) {
            return new UsageHistoryResult.NotFound();
        }
        Optional<RegisteredCommit> registered = register.registered(cloneDir, taskId);
        if (registered.isEmpty()) {
            // throwable-not-subject: the absence is the classification; nothing was thrown.
            log.debug("usage: no factory register names a commit of {}, so no round is recorded", ref);
            return new UsageHistoryResult.Found(List.of(), UsageSnapshot.EMPTY);
        }
        TaskHistory history = TaskHistory.of(runner, cloneDir, registered.get());
        List<UsageRow> rows = walkRows(cloneDir, history.revisions(), new UsageRoundRows(history.taskId()));
        return new UsageHistoryResult.Found(rows, history.recordedTotals());
    }

    private List<UsageRow> walkRows(Path cloneDir, String range, UsageRoundRows rounds) {
        List<UsageRow> rows = new ArrayList<>();
        for (String commit : stateTouchingCommits(cloneDir, range)) {
            StateJsonDto current = readStateAt(cloneDir, commit);
            if (current != null) {
                // null in two cases, both contributing no row: the cleanup commit (FR15) deletes
                // .gnomish-task/, and an unreadable state file, already warned about (FR16).
                rows.addAll(rounds.rowsOf(commit, current));
            }
        }
        return rows;
    }

    /**
     * Every commit of {@code range} on its first-parent line that touches {@code state.json},
     * oldest first ({@code --reverse}). The final {@code Completed} cleanup commit (FR15) appears
     * here (git log reports deletions of a pathspec too), handled by {@link #readStateAt}
     * returning {@code null} for it.
     */
    private List<String> stateTouchingCommits(Path cloneDir, String range) {
        // GitReadGate.answered: an interrupted `git log` would otherwise hand back a prefix of the
        // commit list — a report silently missing its newest rounds.
        GitCommandResult log = GitReadGate.answered(
                range,
                "log",
                runner.run(
                        cloneDir, "log", "--first-parent", "--reverse", "--format=%H", range, "--", STATE_JSON_PATH));
        if (log.exitCode() != 0) {
            // The whole branch's usage report silently becomes empty otherwise (FR5).
            // throwable-not-subject: git reported a status, not a thrown fault.
            UsageHistoryWalker.log.warn(
                    OperatorEvent.USAGE_HISTORY_LISTING_FAILED.head()
                            + "usage: could not list the state-touching commits of {} (git exited {}); the report will be"
                            + " empty: {}",
                    range,
                    log.exitCode(),
                    log.stderr().forLog());
            return List.of();
        }
        // @UntrustedParser warrant (design D11): --format=%H prints object ids, one per line, and
        //     each is used only as a revision argument to the reads below.
        return log.stdout()
                .forParsing()
                .lines()
                .filter(UsageHistoryWalker::isNonBlank)
                .toList();
    }

    /**
     * {@code @DoNotMutate}, provably equivalent: a blank "commit hash" that slipped past this filter
     * fails {@link #readStateAt}'s {@code git show} and contributes no row either way
     * ({@code UsageHistoryWalkerEdgeCasesSpec}'s blank-line feature documents it).
     */
    @DoNotMutate
    private static boolean isNonBlank(String line) {
        return !line.isBlank();
    }

    /**
     * Reads {@code state.json} at {@code commit}, or {@code null} when it is absent there (the
     * cleanup commit, FR15) or unreadable, which is warned about and skipped (FR16 of
     * harden-task-branch-contract).
     */
    private @Nullable StateJsonDto readStateAt(Path cloneDir, String commit) {
        // GitReadGate.answered: an interrupted `git show` would otherwise read as the absent-state
        // case, silently dropping this commit's rounds.
        GitCommandResult show =
                GitReadGate.answered(commit, "show", runner.run(cloneDir, "show", commit + ":" + STATE_JSON_PATH));
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
}
