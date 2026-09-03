package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException;
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskListRow;
import com.github.oinsio.gnomish.app.port.git.TaskListingFailedException;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enumerates every {@code gnomish/*} branch already known to git — local {@code refs/heads/} and
 * remote-tracking {@code refs/remotes/origin/}, read-only, no fetch (FR13's list mode is an
 * overview of what the clone already has, unlike single-task lookup's narrow-fetch fallback) —
 * and reduces them to one {@link TaskListRow} per task, local tip preferred when a task has both.
 *
 * <p>Every branch yields exactly one row whatever its shape (FR16 of harden-task-branch-contract):
 * each ref is classified through {@link TipEnvelopeReader} first, and only a shape whose tip
 * carries readable envelopes is read for its stage, attempts and outcome. Delivered, bare,
 * pre-contract and quarantine branches render from their shape alone, so one unreadable branch
 * degrades to its own diagnostic row instead of failing the listing of the others (UX4).
 *
 * <p>The branch/ref name is never parsed for the taskId (design D16: sanitization is lossy) when the
 * tip can be read: each readable candidate's {@code task.json} is read via {@code git show
 * <ref>:.gnomish-task/task.json} to recover the authoritative taskId, the same idiom {@link
 * BranchStateReader} uses for a single task. A branch whose tip carries no {@code task.json} at all
 * has no other identifier to show, so its row falls back to the branch name — a display label for
 * a branch that is being reported as broken, never an identity claim. Deduplication keys on the
 * branch name throughout, so a task whose local tip is delivered and whose remote tip still carries
 * its files is one row, not two.
 *
 * <p>Per-branch degradation stops at the branch: the <em>enumeration</em> is not one. A ref
 * listing that did not run to its own exit, or that exited non-zero, established nothing about
 * which branches exist, so {@link #list} fails ({@link
 * com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException}, {@link
 * TaskListingFailedException}) rather than returning the empty listing that renders as a verified
 * "no tasks" (FR13 of harden-logging-observability).
 *
 * <p>Implements FR13 of add-git-workflow; FR16, FR2 of harden-task-branch-contract; FR13 of
 * harden-logging-observability.
 */
public final class TaskBranchLister {

    private static final Logger log = LoggerFactory.getLogger(TaskBranchLister.class);

    private static final String LOCAL_PREFIX = "refs/heads/gnomish/";
    private static final String REMOTE_PREFIX = "refs/remotes/origin/gnomish/";

    private final GitProcessRunner runner;
    private final TipEnvelopeReader tipEnvelopeReader = new TipEnvelopeReader();

    public TaskBranchLister(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Lists every distinct task with a {@code gnomish/*} branch in {@code cloneDir}, deduplicated
     * per branch name with the local tip preferred over a remote-tracking one.
     *
     * @param cloneDir the working directory of an existing git clone (the {@code --dir} target)
     * @return one row per task, in the order first encountered (local branches first); empty only
     *     when the enumeration ran and found no {@code gnomish/*} branch anywhere
     * @throws TaskListingFailedException if the ref enumeration exited non-zero
     */
    public List<TaskListRow> list(Path cloneDir) {
        Map<String, TaskListRow> byBranch = new LinkedHashMap<>();
        for (String ref : listRefs(cloneDir, "refs/heads/", LOCAL_PREFIX)) {
            byBranch.put(ref.substring(LOCAL_PREFIX.length()), readRow(cloneDir, ref, LOCAL_PREFIX));
        }
        for (String ref : listRefs(cloneDir, "refs/remotes/origin/", REMOTE_PREFIX)) {
            byBranch.computeIfAbsent(
                    ref.substring(REMOTE_PREFIX.length()), ignored -> readRow(cloneDir, ref, REMOTE_PREFIX));
        }
        return List.copyOf(byBranch.values());
    }

    private List<String> listRefs(Path cloneDir, String pattern, String prefix) {
        GitCommandResult result = runner.run(cloneDir, "for-each-ref", "--format=%(refname)", pattern + "gnomish/*");
        // An enumeration that never ran to its own exit established nothing about which branches
        // exist, so it must not answer with an empty listing — the same non-exit-is-not-a-fact
        // rule the tip reads apply ({@code GitShowTip}), one step earlier in the same pipeline.
        if (result.termination() != Termination.EXITED) {
            throw new BranchTipUnavailableException(
                    pattern + "gnomish/*", "for-each-ref", result.termination().name());
        }
        if (result.exitCode() != 0) {
            // An enumeration that git refused established nothing about which branches exist, so
            // the table it would feed is not an answer (FR13 of harden-logging-observability): the
            // command fails with the git evidence rather than printing "no tasks".
            // DEBUG, not WARN: the decision this failure forces is made one line down and reported
            //     to the operator as a command failure, so a second WARN would be one fault twice.
            // throwable-not-subject: git reported a status, not a thrown fault.
            log.debug(
                    "ref enumeration of {} exited {}: {}",
                    pattern + "gnomish/*",
                    result.exitCode(),
                    LogText.forLog(result.stderr()));
            throw new TaskListingFailedException(
                    pattern + "gnomish/*", result.exitCode(), LogText.forLog(result.stderr()));
        }
        return result.stdout()
                .lines()
                .filter(line -> !line.isBlank() && line.startsWith(prefix))
                .toList();
    }

    /**
     * Classifies one ref and reduces it to its row: content for a tip that carries readable
     * envelopes, the shape alone for every other branch (FR16).
     */
    private TaskListRow readRow(Path cloneDir, String ref, String prefix) {
        BranchTipSource source = new RefTipSource(runner, cloneDir, ref);
        String branchName = ref.substring(prefix.length());
        return switch (tipEnvelopeReader.read(source)) {
            case TipEnvelopeRead.NoState(BranchShape shape) -> new TaskListRow(branchName, null, 0, null, shape);
            case TipEnvelopeRead.Loaded(BranchShape shape, String taskJson, String stateJson) ->
                contentRow(shape, taskJson, stateJson);
        };
    }

    private static TaskListRow contentRow(BranchShape shape, String taskJson, String stateJson) {
        var taskContent = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(taskJson));
        TaskState state = StateJsonMapper.fromDto(StateJsonMapper.readDto(stateJson));
        return new TaskListRow(
                taskContent.context().taskId(),
                stageName(state),
                state.attemptsUsed(),
                outcomeLabel(taskContent.outcome()),
                shape);
    }

    private static @Nullable String stageName(TaskState state) {
        return switch (state.position()) {
            case Position.AtStage atStage -> atStage.name();
            case Position.PipelineEnd ignored -> null;
        };
    }

    private static @Nullable String outcomeLabel(@Nullable RecordedOutcome outcome) {
        return switch (outcome) {
            case null -> null;
            case RecordedOutcome.Completed ignored -> "completed";
            case RecordedOutcome.Paused ignored -> "paused";
            case RecordedOutcome.Escalated ignored -> "escalated";
            case RecordedOutcome.Aborted ignored -> "aborted";
        };
    }
}
