package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.ClosedRound;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * The container end of the pending commit (design D21 of migrate-verify-to-executor-contract,
 * "Container: in the box, through a private index"): the commit is built in the box so the box's
 * {@code HEAD} follows it, and the round's state commit has it as its parent.
 *
 * <ol>
 *   <li>The {@code state.json} bytes go to the box's scratch area, never the working copy, so the
 *       working tree and the index keep what they had — the lag the working-copy check recognises,
 *       settled after a pending round commit only ({@link PendingStateLag#afterLanding}).
 *   <li>In the box, a private index ({@code GIT_INDEX_FILE}) is read from the round's last
 *       registered commit and only {@code state.json} is added: a check that staged a path in the
 *       box's own index cannot put it into the commit. Then {@code commit-tree -p <registered>}, and
 *       {@code update-ref HEAD <commit> <registered>}, a compare-and-swap a check's own commit
 *       refuses. Hooks are off at argv level for every git call.
 *   <li>The harvest, then the factory-side judgement before the branch moves: the parent-check and
 *       the {@code state.json} read-back ({@link HarvestedStateCommitCheck#verifyPending}); the tree
 *       check that nothing outside {@code state.json} changed against the registered commit; and the
 *       admission under {@link AdmissionRule#pending}, which lands the commit with its register in
 *       one ref transaction.
 * </ol>
 *
 * <p>A refusal moves nothing factory-side and throws; the box's {@code HEAD} then names the refused
 * commit, so the round's state commit is not on the registered commit and its parent-check aborts the
 * task. The {@code state.json} carries the registered commit's denial cursor unchanged
 * ({@link EnvironmentRoundDocuments#pendingState}).
 *
 * <p>Implements FR2, FR20, NFR-R1 of migrate-verify-to-executor-contract.
 */
final class InBoxPendingCommit {

    private static final String STATE = EnvelopePaths.STATE_JSON_PATH;

    // Every value travels as a positional argument ($1-$5), never interpolated into the script.
    private static final String SCRIPT =
            "set -e; i=\"$1\"; s=\"$2\"; r=\"$3\"; rm -f \"$i\"; g() { git -c core.hooksPath= \"$@\"; }; "
                    + "GIT_INDEX_FILE=\"$i\" g read-tree \"$r\"; b=$(g hash-object -w --no-filters -- \"$s\"); "
                    + "GIT_INDEX_FILE=\"$i\" g update-index --add --cacheinfo \"100644,$b,$5\"; "
                    + "t=$(GIT_INDEX_FILE=\"$i\" g write-tree); c=$(g commit-tree \"$t\" -p \"$r\" -m \"$4\"); "
                    + "rm -f \"$i\" \"$s\"; g update-ref HEAD \"$c\" \"$r\"";

    private final TaskExecutionEnvironment environment;
    private final GitRunner runner;
    private final Path cloneDir;
    private final GitObjects gitObjects;
    private final ClaimEpochSource epochs;
    private final HarvestAdmission admission;

    InBoxPendingCommit(
            TaskExecutionEnvironment environment,
            GitRunner runner,
            Path cloneDir,
            GitObjects gitObjects,
            ClaimEpochSource epochs) {
        this.environment = environment;
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.gitObjects = gitObjects;
        this.epochs = epochs;
        this.admission = new HarvestAdmission(runner, cloneDir);
    }

    /**
     * Lands the pending commit on {@code round}'s last registered commit.
     *
     * @return the pending commit, admitted with its register
     * @throws RoundBoundaryViolationException if the harvested commit is not the one the factory
     *     asked for: another parent, other content, another path
     * @throws GitPersistFailedException if a step failed or could not be judged
     */
    String land(PendingWrite write, TaskState state, ClosedRound round) {
        String taskId = write.taskId();
        AttemptKey key = write.key();
        String registered = round.registered();
        // Read only: the writer here builds nothing, it carries the registered commit's cursor forward.
        var cursor = new TaskLifecycleCommitWriter(
                        gitObjects, TaskLifecycleCommitWriter.FACTORY_IDENTITY, Instant.EPOCH, epochs)
                .tipStateCursor(taskId, ObjectId.of(registered));
        byte[] stateBytes = EnvironmentRoundDocuments.pendingState(taskId, key, state, cursor);
        String scratch = environment.scratchRoot();
        String statePath = scratch + "/gnomish-pending-state.json";
        environment.putFile(statePath, stateBytes);
        String message =
                ClaimEpochTrailer.stamp(write.subject(), epochs.epochFor(taskId).orElse(null));
        InBoxGitCommand.Outcome commit = new InBoxGitCommand(environment)
                .run(
                        "in-box pending commit",
                        SCRIPT,
                        List.of("gnomish", scratch + "/gnomish-pending.idx", statePath, registered, message, STATE));
        if (!commit.succeeded()) {
            throw new GitPersistFailedException(
                    taskId, key.stage(), key.attempt(), "in-box pending commit", commit.output());
        }
        environment.harvest();
        String pending = admission.quarantined(taskId);
        new HarvestedStateCommitCheck(gitObjects).verifyPending(taskId, pending, registered, stateBytes);
        onlyStateChanged(key, registered, pending);
        switch (admission.admit(taskId, pending, AdmissionRule.pending(write.subject()))) {
            case AdmittedRange.Admitted ignored -> {}
            case AdmittedRange.Refused refused -> throw new RoundBoundaryViolationException(taskId, refused.reason());
            case AdmittedRange.Unverified unverified ->
                throw new GitPersistFailedException(
                        taskId, key.stage(), key.attempt(), "pending admission", unverified.evidence());
        }
        return pending;
    }

    /** The whole tree, not only the state directory: the pending commit changes {@code state.json} alone. */
    private void onlyStateChanged(AttemptKey key, String registered, String pending) {
        GitCommandResult diff = runner.run(
                cloneDir,
                "diff-tree",
                "--quiet",
                "-r",
                "--no-renames",
                registered,
                pending,
                "--",
                ".",
                ":(exclude)" + STATE);
        if (diff.termination() == Termination.EXITED && diff.exitCode() == 1) {
            throw new RoundBoundaryViolationException(
                    key.taskId(),
                    UntrustedText.factory("the harvested pending commit " + pending + " changes a path besides " + STATE
                            + " (a change was carried in inside the environment)"));
        }
        if (!diff.succeeded()) {
            throw new GitPersistFailedException(
                    key.taskId(), key.stage(), key.attempt(), "pending commit tree check", diff.cannotVerifyDetail());
        }
    }
}
