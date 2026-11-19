package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.nio.file.Path;

/**
 * Closes the gnome half of a sandboxed round (FR21, design D15 of add-sandbox-core): the container's
 * trigger of the one {@link RoundSnapshot} step at the round close, over the box medium ({@link
 * InBoxSnapshot}). Verification then judges exactly the harvested commit: builtin checks read it as
 * bare objects in the factory clone, fresh-box checks and judge votes materialize from it, external
 * checks poll CI runs of exactly the pushed commit.
 *
 * <p>The round's executor names the close (design D22 of migrate-verify-to-executor-contract): a
 * round it did not complete is snapshotted under the unverified subject, so a resume after a death
 * before its state commit re-runs the stage executor instead of verifying a failed round. The
 * snapshot commit deliberately includes any pending decision file (D17): it rides the same commit
 * so a pending escalation survives any death. The subject carries the round's token, taken from
 * the run's {@link CurrentRound#opened()} — the one minting is {@link
 * SandboxRoundEnvironmentSource#openRound}, never a tip read here (FR15 of
 * make-checkpoint-gate-durable, design D10).
 *
 * <p>Implements FR21 of add-sandbox-core; FR15 of make-checkpoint-gate-durable; FR19 of
 * migrate-verify-to-executor-contract.
 */
public final class EnvironmentRoundSnapshot {

    private final RoundSnapshot step;

    /**
     * @param environment the task's bound environment; snapshot commits run through its exec
     * @param runner the git subprocess runner reading the harvest factory-side
     * @param cloneDir the factory clone harvest lands in
     * @param rounds the run's round cell; the subject names the token the round opened with, and
     *     the harvested snapshot closes that round in it
     */
    public EnvironmentRoundSnapshot(
            TaskExecutionEnvironment environment, GitRunner runner, Path cloneDir, CurrentRound rounds) {
        this.step = new RoundSnapshot(new InBoxSnapshot(environment, runner, cloneDir), rounds);
    }

    /**
     * Commits the working tree in-box, harvests, admits, and records the harvested commit as the
     * round's attempt commit.
     *
     * @param taskId the task whose round is closing
     * @param stage the current stage id
     * @param round the round's number
     * @param close whether the round's executor completed
     * @return the harvested attempt commit id
     * @throws IllegalStateException if no round opened — a snapshot names the round that owns it
     * @throws GitPersistFailedException if the in-box snapshot commit fails
     */
    public String snapshot(String taskId, String stage, int round, RoundClose close) {
        // The box judges its range at the admission, so the step never declines it.
        return step.take(taskId, stage, round, close).orElseThrow();
    }
}
