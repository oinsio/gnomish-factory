package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.app.port.git.RoundToken;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes the gnome half of a sandboxed round (FR21, design D15): a snapshot
 * commit of the whole working tree executed <em>inside</em> the environment —
 * hooks disabled at argv level, as the in-box user, {@code --allow-empty} so a
 * round that changed nothing still yields a distinct attempt commit for
 * verification to judge — then a harvest into the task's quarantine and the
 * admission of what landed ({@link HarvestAdmission}, design D18 of
 * tighten-spec-contract), and the harvested commit is recorded into the run's
 * {@link CurrentRound} as the snapshot that closes the open round. Verification
 * then judges exactly that commit: builtin checks read it as bare objects in the
 * factory clone, fresh-box checks and judge votes materialize from it, external
 * checks poll CI runs of exactly the pushed commit.
 *
 * <p>An admitted snapshot is a closing commit (design D13): the branch and the
 * register move to it in one ref transaction, so a resume after a death before
 * the state commit finds it registered. No push here — the state commit's
 * closing push, or the attempt delivery an external check asks for, carries
 * branch and register to origin together. A refused or unverifiable snapshot
 * moves nothing; it is still the round's attempt commit, and the verdict is the
 * round's persist, which judges it again ({@link EnvironmentAttemptPersistence})
 * and aborts the task on a violation — the place the engine turns a thrown
 * boundary into {@code Aborted}, rather than into an executor that could not run.
 *
 * <p>Runs as the tail of the executor adapter's {@code execute()} — the engine
 * stays untouched (D15). The snapshot commit deliberately includes any pending
 * decision file (D17): it rides the same commit so a pending escalation
 * survives any death.
 *
 * <p>The subject carries the round's token, taken from the run's {@link CurrentRound#opened()} —
 * the one minting is {@link SandboxRoundEnvironmentSource#openRound}, never a tip read here — so a
 * resume after a death between this commit and the state commit reads the request at exactly
 * this round's path in the snapshot's tree (FR15 of make-checkpoint-gate-durable, design D10).
 *
 * <p>Implements FR21 of add-sandbox-core; FR13 of harden-logging-observability; FR15 of
 * make-checkpoint-gate-durable; FR13, NFR-S2 of tighten-spec-contract.
 */
public final class EnvironmentRoundSnapshot {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentRoundSnapshot.class);

    // The commit message travels as a positional arg ($1), never string-interpolated into the
    // script, so it cannot carry a shell metacharacter that alters the command — the same
    // defense-in-depth pattern EnvironmentAttemptPersistence uses for factory-authored content.
    private static final String SNAPSHOT_SCRIPT = "git add -A && git -c core.hooksPath= commit --allow-empty -m \"$1\"";

    private final TaskExecutionEnvironment environment;
    private final CurrentRound rounds;
    private final HarvestAdmission admission;

    /**
     * @param environment the task's bound environment; snapshot commits run through its exec
     * @param runner the git subprocess runner reading the harvest factory-side
     * @param cloneDir the factory clone harvest lands in
     * @param rounds the run's round cell; the subject names the token the round opened with, and
     *     the harvested snapshot closes that round in it
     */
    public EnvironmentRoundSnapshot(
            TaskExecutionEnvironment environment, GitRunner runner, Path cloneDir, CurrentRound rounds) {
        this.environment = environment;
        this.rounds = rounds;
        this.admission = new HarvestAdmission(runner, cloneDir);
    }

    /**
     * Commits the working tree in-box, harvests, admits, and records the harvested commit as the
     * round's attempt commit.
     *
     * @param taskId the task whose round is closing
     * @param stage the current stage id
     * @param round the round's 1-based number
     * @return the harvested attempt commit id
     * @throws IllegalStateException if no round opened — a snapshot names the round that owns it
     * @throws GitPersistFailedException if the in-box snapshot commit fails
     * @throws com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException if the
     *     harvested commit cannot be resolved — no attempt commit is recorded blank (FR13 of
     *     harden-logging-observability)
     */
    public String snapshot(String taskId, String stage, int round) {
        RoundToken token = rounds.opened();
        String message = ServiceCommitMessages.snapshot(stage, round, token);
        // Run through the medium's one outcome seam (D14): the wait is drained concurrently
        // (FR2, FR11 of bound-subprocess-commands) and an interrupted wait comes back named
        // rather than as an exception this site would have to classify itself.
        InBoxGitCommand.Outcome commit = new InBoxGitCommand(environment)
                .run("in-box snapshot commit", SNAPSHOT_SCRIPT, List.of("gnomish", message));
        if (!commit.succeeded()) {
            throw new GitPersistFailedException(taskId, stage, round, "in-box snapshot commit", commit.output());
        }

        environment.harvest();

        // The harvested commit is recorded as the round's attempt commit and judged by every
        // verification check that follows, so a failed resolution must fail the snapshot rather
        // than record the empty string as a commit (FR13 of harden-logging-observability).
        String snapshot = admission.quarantined(taskId);
        AdmittedRange admitted =
                admission.admit(taskId, snapshot, AdmissionRule.snapshot(new AttemptKey(taskId, stage, round), token));
        // Not the verdict: a snapshot the admission refused moves nothing, and the persist judges the
        // same commit again and throws it (one fault, one log at the layer that decides).
        log.debug("snapshot {} of task {} closes the round; its admission: {}", snapshot, taskId, admitted);
        rounds.snapshotted(snapshot);
        return snapshot;
    }
}
