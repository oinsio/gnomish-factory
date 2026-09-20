package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TraceLineWriter;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.nio.file.Path;
import java.util.List;

/**
 * The sandboxed realization of the engine's {@link AttemptPersistence} port
 * (FR21, FR22, design D15, D16): where host mode closes a round as one worktree
 * commit ({@link GitAttemptPersistence}), a sandboxed round closes in two steps
 * — the executor's snapshot commit ({@link EnvironmentRoundSnapshot}, already
 * harvested and verified by the time this port is called) and this class's
 * <b>state commit</b>: {@code state.json} and the round trace written through
 * the environment channel ({@code putFile}), committed in-box (hooks off at
 * argv level), harvested, and then verified at the boundary.
 *
 * <p>Harvest-boundary integrity (D16) is factory-side and trusted, and owned by
 * two checks this class only sequences: the <b>boundary protocol</b> — {@code
 * .gnomish-task/} untouched by the gnome between the previous tip and the
 * snapshot commit, with the single decision-file carve-out ({@link
 * HarvestedBoundaryCheck}, FR23) — and, on the harvested state commit itself, the
 * parent-check and the byte-exact read-back ({@link HarvestedStateCommitCheck},
 * FR22).
 *
 * <p>The branch tip this class resolves — the baseline for the next round's
 * boundary check and the commit the read-back reads from — is verified before
 * use ({@link VerifiedTip}): a failed or blank resolution fails the persist with
 * the git evidence rather than being recorded, the same rule its host twin
 * {@link GitAttemptPersistence} applies to its own round baseline.
 *
 * <p>Any mismatch throws {@link RoundBoundaryViolationException}; the engine
 * turns a thrown persist into {@code Aborted}, the branch keeps the evidence,
 * and the environment is kept untouched. This is a strict port: any failure to
 * durably commit throws, never returns.
 *
 * <p>Kept in sync with {@link GitAttemptPersistence}: both must close a round
 * with the same durable sequence — {@code state.json} and the round's trace file
 * written first, then one commit stamped with the claim epoch that carries both,
 * so neither medium can leave a branch on which a round's state landed without
 * its trace (or the other way round). The denial cursor this class commits inside
 * {@code state.json} ({@link EnvironmentRoundDocuments}) is deliberately
 * environment-side only and is not part of the synchronized invariant: host mode
 * has no egress guard, so there is no denial source to mirror.
 *
 * <p>Kill windows (crash-consistency rule). The durable step is the harvest: the
 * in-box commit lives in a disposable environment, so it becomes branch state only
 * once the fast-forward-only fetch lands it in the factory clone, and the push to
 * origin is a later step owned by {@link PushBestEffortAttemptPersistence}. A kill
 * before the harvest — during the {@code putFile} writes, or between them and the
 * in-box commit — dies with the box and freezes "round not closed": the clone and
 * origin still read the previous tip, and the next pickup sees no round rather than
 * a half-written one. A kill after the harvest but before the push freezes "round
 * harvested locally, not pushed", the same window the host twin's commit-before-push
 * gap leaves, converged by the next push of this branch. The sequence is not atomic
 * across those steps and does not claim to be.
 *
 * <p>Implements FR21, FR22, FR23 of add-sandbox-core; FR13 of
 * harden-logging-observability.
 */
public final class EnvironmentAttemptPersistence implements AttemptPersistence {

    private static final String STATE_PATH = GnomishTaskPaths.STATE_JSON_PATH;

    // Paths and the commit message travel as positional args ($1-$3), never string-interpolated
    // into the script, so neither can carry a shell metacharacter that alters the command —
    // the same defense-in-depth pattern ContainerFileChannel uses for factory-authored content.
    private static final String COMMIT_SCRIPT = "git add -- \"$1\" \"$2\" && git -c core.hooksPath= commit -m \"$3\"";

    private final TaskExecutionEnvironment environment;
    private final GitProcessRunner runner;
    private final Path cloneDir;
    private final String branch;
    private final AttemptCommitRef attemptCommit;
    private final HarvestedBoundaryCheck boundaryCheck;
    private final HarvestedStateCommitCheck stateCommitCheck;
    private final EnvironmentRoundDocuments documents;
    private final ClaimEpochSource epochs;
    private String previousTip;

    /**
     * @param environment the task's bound environment; state files and the state commit cross it
     * @param runner the git subprocess runner for factory-side ref reads
     * @param cloneDir the factory clone harvest lands in
     * @param gitObjects the bare-object facade opened against the factory clone, for byte-exact
     *     read-back (D16)
     * @param taskId the tracker's original taskId; sanitized into the task branch name
     * @param attemptCommit the run's attempt-commit ref, recorded by the snapshot step
     * @param epochs the tenure the in-box state commits are stamped with (FR13 of
     *     harden-task-branch-contract); {@link ClaimEpochSource#NONE} where no claim is held
     */
    public EnvironmentAttemptPersistence(
            TaskExecutionEnvironment environment,
            GitProcessRunner runner,
            Path cloneDir,
            GitObjects gitObjects,
            String taskId,
            AttemptCommitRef attemptCommit,
            ClaimEpochSource epochs) {
        this.environment = environment;
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.branch = TaskIdSanitizer.branchName(taskId);
        this.attemptCommit = attemptCommit;
        this.boundaryCheck = new HarvestedBoundaryCheck(runner, cloneDir);
        this.stateCommitCheck = new HarvestedStateCommitCheck(gitObjects);
        this.documents = new EnvironmentRoundDocuments(environment);
        this.epochs = epochs;
        this.previousTip = currentTip();
    }

    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        AttemptKey key = trace.key();
        String snapshot = attemptCommit.required();

        boundaryCheck.verify(taskId, previousTip, snapshot, key);

        byte[] stateBytes = documents.state(taskId, key, state);
        byte[] traceBytes = EnvironmentRoundDocuments.trace(trace);
        String tracePath = GnomishTaskPaths.DIR + TraceLineWriter.relativePath(key);
        environment.putFile(STATE_PATH, stateBytes);
        environment.putFile(tracePath, traceBytes);

        commitInBox(taskId, key, tracePath);
        environment.harvest();

        String tip = currentTip();
        stateCommitCheck.verify(taskId, tip, snapshot, tracePath, stateBytes, traceBytes);

        previousTip = tip;
    }

    private void commitInBox(String taskId, AttemptKey key, String tracePath) {
        // Only the two factory files are staged: gnome residue outside .gnomish-task/ belongs to
        // the next round (or salvage), never to the state commit (D15).
        String message = ClaimEpochTrailer.stamp(
                ServiceCommitMessages.round(key.stage(), key.attempt()),
                epochs.epochFor(taskId).orElse(null));
        // Run through the medium's one outcome seam (D14): the wait is drained concurrently, so a
        // hung in-box command never holds this thread on an uninterruptible pipe read (FR2, FR11
        // of bound-subprocess-commands), and an interrupted wait comes back named.
        InBoxGitCommand.Outcome commit = new InBoxGitCommand(environment)
                .run("in-box state commit", COMMIT_SCRIPT, List.of("gnomish", STATE_PATH, tracePath, message));
        if (!commit.succeeded()) {
            throw new GitPersistFailedException(
                    taskId, key.stage(), key.attempt(), "in-box state commit", commit.output());
        }
    }

    /**
     * The harvested branch tip, verified before it is used (FR13 of harden-logging-observability):
     * this value becomes the previous tip the next round's boundary check compares against and the
     * commit the read-back reads its blobs from, so a failed resolution must fail the persist
     * rather than travel on as the empty string.
     */
    private String currentTip() {
        String revision = "refs/heads/" + branch;
        return VerifiedTip.required(revision, "rev-parse", runner.run(cloneDir, "rev-parse", revision));
    }
}
