package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The box's {@link SnapshotMedium} (FR21 of add-sandbox-core, design D15; design D22 of
 * migrate-verify-to-executor-contract): a commit of the whole working tree executed <em>inside</em>
 * the environment — hooks disabled at argv level, as the in-box user, {@code --allow-empty} so a round
 * that changed nothing still yields a distinct attempt commit — then a harvest into the task's
 * quarantine and the admission of what landed ({@link HarvestAdmission}, design D18 of
 * tighten-spec-contract), under the subject the step chose.
 *
 * <p>The box's range is judged at its admission, where the gnome's in-box commits first become
 * visible to the factory, so this medium's verdict before the commit is always clean. The admission
 * judges {@code register..snapshot}, and the register names the round's last registered commit, so
 * a round that went pending is judged from its last pending round commit — the box's {@code HEAD},
 * which the in-box pending commit moved, and so the snapshot's parent (design D11 of
 * extract-agent-round-runner, as amended 2026-11-25). Before the commit stages the working tree, the
 * {@code state.json} that commit left lagging in the box's index and working tree is settled in the
 * box ({@link PendingStateLag}), so the snapshot changes no path under the state directory the
 * admission refuses. An admitted
 * snapshot is a closing commit (design D13 of tighten-spec-contract): the branch and the register
 * move to it in one ref transaction. A refused or unverifiable snapshot moves nothing; it is still
 * the round's attempt commit, and the round's persist judges it again
 * ({@link EnvironmentAttemptPersistence}) and aborts the task on a violation.
 *
 * <p>No push here — the state commit's closing push, or the attempt delivery an external check asks
 * for, carries branch and register to origin together.
 *
 * <p>Implements FR21 of add-sandbox-core; FR13 of harden-logging-observability; FR13, NFR-S2 of
 * tighten-spec-contract; FR19 of migrate-verify-to-executor-contract; FR10 of
 * extract-agent-round-runner.
 */
final class InBoxSnapshot implements SnapshotMedium {

    private static final Logger log = LoggerFactory.getLogger(InBoxSnapshot.class);

    // The commit message travels as a positional arg ($1), never string-interpolated into the
    // script, so it cannot carry a shell metacharacter that alters the command — the same
    // defense-in-depth pattern EnvironmentAttemptPersistence uses for factory-authored content.
    private static final String SNAPSHOT_SCRIPT = "git add -A && git -c core.hooksPath= commit --allow-empty -m \"$1\"";

    private final TaskExecutionEnvironment environment;
    private final HarvestAdmission admission;
    private final PendingStateLag lag;

    /**
     * @param environment the task's bound environment; snapshot commits run through its exec
     * @param runner the git subprocess runner reading the harvest factory-side
     * @param cloneDir the factory clone harvest lands in
     */
    InBoxSnapshot(TaskExecutionEnvironment environment, GitRunner runner, Path cloneDir) {
        this.environment = environment;
        this.admission = new HarvestAdmission(runner, cloneDir);
        this.lag = new PendingStateLag(WorkingCopyGit.inBox(environment));
    }

    @Override
    public Optional<UntrustedText> settle(SnapshotOrder order) {
        return lag.settle(order.chain());
    }

    @Override
    public RangeVerdict judge(SnapshotOrder order) {
        return new RangeVerdict.Clean();
    }

    /**
     * @throws GitPersistFailedException if the in-box snapshot commit fails
     * @throws com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException if the harvested
     *     commit cannot be resolved — no attempt commit is recorded blank (FR13 of
     *     harden-logging-observability)
     */
    @Override
    public String commit(SnapshotOrder order) {
        AttemptKey key = order.key();
        // Run through the medium's one outcome seam (D14): the wait is drained concurrently (FR2,
        // FR11 of bound-subprocess-commands) and an interrupted wait comes back named rather than as
        // an exception this site would have to classify itself.
        InBoxGitCommand.Outcome commit = new InBoxGitCommand(environment)
                .run("in-box snapshot commit", SNAPSHOT_SCRIPT, List.of("gnomish", order.subject()));
        if (!commit.succeeded()) {
            throw new GitPersistFailedException(
                    key.taskId(), key.stage(), key.attempt(), "in-box snapshot commit", commit.output());
        }

        environment.harvest();

        String snapshot = admission.quarantined(key.taskId());
        AdmittedRange admitted =
                admission.admit(key.taskId(), snapshot, AdmissionRule.snapshot(key, order.token(), order.subject()));
        // Not the verdict: a snapshot the admission refused moves nothing, and the persist judges the
        // same commit again and throws it (one fault, one log at the layer that decides).
        log.debug("snapshot {} of task {} closes the round; its admission: {}", snapshot, key.taskId(), admitted);
        return snapshot;
    }
}
