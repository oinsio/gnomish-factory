package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef;
import com.github.oinsio.gnomish.sandbox.CapturedExec;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Closes the gnome half of a sandboxed round (FR21, design D15): a snapshot
 * commit of the whole working tree executed <em>inside</em> the environment —
 * hooks disabled at argv level, as the in-box user, {@code --allow-empty} so a
 * round that changed nothing still yields a distinct attempt commit for
 * verification to judge — then a harvest, and the harvested tip is recorded
 * into the round's {@link AttemptCommitRef}. Verification then judges exactly
 * that commit: builtin checks read it as bare objects in the factory clone,
 * fresh-box checks and judge votes materialize from it, external checks poll
 * CI runs of exactly the pushed commit.
 *
 * <p>Runs as the tail of the executor adapter's {@code execute()} — the engine
 * stays untouched (D15). The snapshot commit deliberately includes any pending
 * decision file (D17): it rides the same commit so a pending escalation
 * survives any death. A {@link HarvestRefusedException} (rewritten history)
 * propagates as the existing violation.
 *
 * <p>Implements FR21 of add-sandbox-core.
 */
public final class EnvironmentRoundSnapshot {

    // The commit message travels as a positional arg ($1), never string-interpolated into the
    // script, so it cannot carry a shell metacharacter that alters the command — the same
    // defense-in-depth pattern EnvironmentAttemptPersistence uses for factory-authored content.
    private static final String SNAPSHOT_SCRIPT = "git add -A && git -c core.hooksPath= commit --allow-empty -m \"$1\"";

    private final TaskExecutionEnvironment environment;
    private final GitProcessRunner runner;
    private final Path cloneDir;
    private final String branch;
    private final AttemptCommitRef attemptCommit;

    /**
     * @param environment the task's bound environment; snapshot commits run through its exec
     * @param runner the git subprocess runner reading the harvested tip factory-side
     * @param cloneDir the factory clone harvest lands in
     * @param taskId the tracker's original taskId; sanitized into the task branch name
     * @param attemptCommit the run's attempt-commit ref, updated with each harvested snapshot
     */
    public EnvironmentRoundSnapshot(
            TaskExecutionEnvironment environment,
            GitProcessRunner runner,
            Path cloneDir,
            String taskId,
            AttemptCommitRef attemptCommit) {
        this.environment = environment;
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.branch = TaskIdSanitizer.branchName(taskId);
        this.attemptCommit = attemptCommit;
    }

    /**
     * Commits the working tree in-box, harvests, and records the harvested tip
     * as the round's attempt commit.
     *
     * @param taskId the task whose round is closing, for error context
     * @param stage the current stage id
     * @param round the round's 1-based number
     * @return the harvested attempt commit id
     * @throws GitPersistFailedException if the in-box snapshot commit fails
     * @throws HarvestRefusedException if the branch history was rewritten in-box
     */
    public String snapshot(String taskId, String stage, int round) {
        String message = ServiceCommitMessages.snapshot(stage, round);
        List<String> argv = List.of("sh", "-c", SNAPSHOT_SCRIPT, "gnomish", message);
        ExecHandle handle = environment.exec(new ExecCommand(argv, Map.of(), null, true));
        // Drained concurrently with the supervised wait (FR2, FR11 of bound-subprocess-commands).
        CapturedExec commit = CapturedExec.of(handle, "in-box snapshot commit");
        if (commit.exitCode() != 0) {
            throw new GitPersistFailedException(taskId, stage, round, "in-box snapshot commit", commit.output());
        }

        environment.harvest();

        String tip = runner.run(cloneDir, "rev-parse", "refs/heads/" + branch)
                .stdout()
                .trim();
        attemptCommit.record(tip);
        return tip;
    }
}
