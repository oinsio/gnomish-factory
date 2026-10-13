package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TraceLineWriter;
import com.github.oinsio.gnomish.app.port.git.ClosedRound;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
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
 * harvested by the time this port is called) and this class's <b>state
 * commit</b>: {@code state.json} and the round trace written through the
 * environment channel ({@code putFile}), committed in-box (hooks off at argv
 * level), harvested into the task's quarantine, checked, and admitted.
 *
 * <p>Harvest-boundary integrity (D16; design D18 of tighten-spec-contract) is
 * factory-side and trusted, and owned by two checks this class only sequences:
 * the <b>admission</b> ({@link HarvestAdmission}, the one {@link
 * GnomeRangeInspection} of the range above the register — FR23) and, on the
 * harvested state commit itself, the parent-check and the byte-exact read-back
 * ({@link HarvestedStateCommitCheck}, FR22). The snapshot is judged first, again:
 * an admitted snapshot is already on the branch and passes without a move, while
 * one its admission refused is a boundary violation here, thrown before anything
 * is written in-box — the persist is where the engine turns a boundary into
 * {@code Aborted}. The state commit is judged before the branch moves to it, so a
 * refused one never becomes branch state.
 *
 * <p>The round being persisted is read whole from the run's {@link CurrentRound}
 * cell (design D10 of make-checkpoint-gate-durable): its token names the
 * carve-out, its attempt commit the snapshot the state commit must sit on. A
 * live round put them there when it opened and snapshotted; a resumed round
 * restored them from the snapshot's durable record. This class keeps no baseline
 * of its own and never re-reads the tip to stand in for a round the cell does not
 * hold: a persist with no closed round throws.
 *
 * <p>Any violation throws {@link RoundBoundaryViolationException}; the engine
 * turns a thrown persist into {@code Aborted}, the branch keeps the evidence it
 * admitted, and the environment is kept untouched. A verdict git could not
 * produce throws {@link GitPersistFailedException}, the round's infrastructure
 * path. This is a strict port: any failure to durably commit throws, never
 * returns.
 *
 * <p>Kept in sync with {@link GitAttemptPersistence}: both must close a round
 * with the same durable sequence — {@code state.json} and the round's trace file
 * written first, then one commit stamped with the claim epoch that carries both,
 * so neither medium can leave a branch on which a round's state landed without
 * its trace (or the other way round) — judge the gnome's range with the one
 * {@link GnomeRangeInspection} before it becomes part of the round, keep the
 * factory-owned paths of the round commit the factory's own (here the in-box
 * commit stages exactly the two files and the admission refuses any other owned
 * path; there the worktree is restored from the register before staging), and
 * land the round commit with its register in one ref transaction, carried to
 * origin by one atomic push (design D13, D18 of tighten-spec-contract). The
 * denial cursor this class commits inside {@code state.json} ({@link
 * EnvironmentRoundDocuments}) is deliberately environment-side only and is not
 * part of the synchronized invariant: host mode has no egress guard, so there is
 * no denial source to mirror. Nor is the round's decision carve-out: host mode's
 * request never enters the working copy, so its inspection permits no path.
 *
 * <p>Kill windows (crash-consistency rule). The durable step is the admission's
 * ref transaction: the in-box commit and the quarantine are scratch until the
 * branch and the register move to the state commit together, and the push to
 * origin is a later step owned by {@link PushBestEffortAttemptPersistence}. A
 * kill before the transaction — during the {@code putFile} writes, the in-box
 * commit, the harvest or the checks — freezes "round not closed": the branch and
 * the register still name the snapshot, and the next pickup resumes the
 * interrupted verification from it. A kill after the transaction but before the
 * push freezes "round closed locally, not pushed", converged by the next push of
 * this branch.
 *
 * <p>Implements FR21, FR22, FR23 of add-sandbox-core; FR13 of
 * harden-logging-observability; FR13, FR15 of make-checkpoint-gate-durable; FR13,
 * NFR-S2 of tighten-spec-contract.
 */
public final class EnvironmentAttemptPersistence implements AttemptPersistence {

    private static final String STATE_PATH = EnvelopePaths.STATE_JSON_PATH;

    // Paths and the commit message travel as positional args ($1-$3), never string-interpolated
    // into the script, so neither can carry a shell metacharacter that alters the command —
    // the same defense-in-depth pattern ContainerFileChannel uses for factory-authored content.
    private static final String COMMIT_SCRIPT = "git add -- \"$1\" \"$2\" && git -c core.hooksPath= commit -m \"$3\"";

    private final TaskExecutionEnvironment environment;
    private final CurrentRound rounds;
    private final HarvestAdmission admission;
    private final HarvestedStateCommitCheck stateCommitCheck;
    private final EnvironmentRoundDocuments documents;
    private final ClaimEpochSource epochs;

    /**
     * @param environment the task's bound environment; state files and the state commit cross it
     * @param runner the git subprocess runner for factory-side ref reads and the admission
     * @param cloneDir the factory clone harvest lands in
     * @param gitObjects the bare-object facade opened against the factory clone, for byte-exact
     *     read-back (D16)
     * @param rounds the run's round cell; the closed round names the carve-out, the diff base and the
     *     snapshot this persist judges
     * @param epochs the tenure the in-box state commits are stamped with (FR13 of
     *     harden-task-branch-contract); {@link ClaimEpochSource#NONE} where no claim is held
     */
    public EnvironmentAttemptPersistence(
            TaskExecutionEnvironment environment,
            GitRunner runner,
            Path cloneDir,
            GitObjects gitObjects,
            CurrentRound rounds,
            ClaimEpochSource epochs) {
        this.environment = environment;
        this.rounds = rounds;
        this.admission = new HarvestAdmission(runner, cloneDir);
        this.stateCommitCheck = new HarvestedStateCommitCheck(gitObjects);
        this.documents = new EnvironmentRoundDocuments(environment);
        this.epochs = epochs;
    }

    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        AttemptKey key = trace.key();
        // Throws when the cell holds no closed round: there is no fallback read of the tip (design
        // D10 of make-checkpoint-gate-durable, alternative C rejected).
        ClosedRound round = rounds.closed();
        String snapshot = round.attemptCommit();

        // The token is the tip the round opened on: the name of the one permitted decision file,
        // one value recorded once (FR13, FR15). An admitted snapshot passes without a move.
        judged(taskId, key, admission.admit(taskId, snapshot, AdmissionRule.snapshot(key, round.token())));

        byte[] stateBytes = documents.state(taskId, key, state);
        byte[] traceBytes = EnvironmentRoundDocuments.trace(trace);
        String tracePath = EnvelopePaths.DIR + TraceLineWriter.relativePath(key);
        environment.putFile(STATE_PATH, stateBytes);
        environment.putFile(tracePath, traceBytes);

        commitInBox(taskId, key, tracePath);
        environment.harvest();

        // A fresh observation of the harvest, judged against the record before the branch moves: the
        // state commit must sit on the round's own snapshot and carry exactly what was written.
        String stateCommit = admission.quarantined(taskId);
        stateCommitCheck.verify(taskId, stateCommit, snapshot, tracePath, stateBytes, traceBytes);
        judged(taskId, key, admission.admit(taskId, stateCommit, AdmissionRule.state(key, round.token(), tracePath)));
    }

    /** The admission's verdict as this port's outcome: a violation, or the infrastructure path. */
    private static void judged(String taskId, AttemptKey key, AdmittedRange admitted) {
        switch (admitted) {
            case AdmittedRange.Admitted ignored -> {}
            case AdmittedRange.Refused refused -> throw new RoundBoundaryViolationException(taskId, refused.reason());
            case AdmittedRange.Unverified unverified ->
                throw new GitPersistFailedException(
                        taskId, key.stage(), key.attempt(), "harvest admission", unverified.evidence());
        }
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
}
