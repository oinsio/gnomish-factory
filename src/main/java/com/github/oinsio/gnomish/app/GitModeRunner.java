package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository;
import com.github.oinsio.gnomish.adapter.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.adapter.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.adapter.git.TaskWorktreeCleanup;
import com.github.oinsio.gnomish.adapter.git.TaskWorktreePath;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Drives the fresh-run git-mode path (design D8 of add-git-workflow, task 4.4): the {@code
 * --mode=git} counterpart of {@link ManualRunAssembly}'s in-place wiring, wired for a brand-new
 * task only — {@code --resume} is task 4.6 onward and is not handled here.
 *
 * <p>Sequence, per FR6/FR7/UX1: prune stale worktree registrations, print the deterministic
 * branch name and worktree path <em>before</em> anything else runs (UX1 — "the operator always
 * knows where the work lives"; both names are pure functions of {@code cloneDir}/{@code taskId},
 * computed without any git call, see {@link #branchName}/{@link #worktreePath}), then record task
 * start via {@link GitTaskRepository#createTask} — the single call that actually creates the
 * branch off {@code --base} (or the clone's current {@code HEAD} when absent, design D7) and
 * materializes the worktree — then assemble {@code EnginePorts} with a fresh {@link
 * GitAttemptPersistence} rooted at the worktree — not {@code --dir} — as the workspace (FR7: "the
 * clone itself is untouched"). {@link GitTaskRepository#createTask} is deliberately the only
 * caller of branch/worktree creation in this sequence: a second, independent creation attempt
 * here would race it and see a spurious "already exists" on its own first call.
 *
 * <p>{@link GitTaskRepository#createTask} throws {@link GitTaskRepositoryException} for both an
 * already-existing branch and an unresolved {@code --base}; {@link #run} remaps that exception to
 * a {@link UsageException} (exit code 2) here: this is a fresh run, not a resume, so either
 * condition names an operator mistake the run cannot proceed past, not a resumable one.
 *
 * <p>Task 4.5 (NFR-S2, workspace hygiene): pointing the workspace at the worktree here does not,
 * by itself, risk decision-file temp dirs or logs entering the round commit. Both are already
 * anchored outside any workspace root by construction, unchanged by this class: {@code
 * CliStageExecutor}'s {@code DecisionFileTransport} always roots each round's temp directory
 * under {@code java.io.tmpdir} (never under the {@link DirectoryWorkspace} it is handed), and
 * {@code logback-spring.xml} writes the instance's rolling log file under {@code
 * ~/.gnomish/logs/} — both independent of {@code --dir}/worktree/workspace entirely. See {@code
 * GitModeWorkspaceHygieneSpec} for the regression proof: a real round's commit tree contains only
 * the gnome's own change and {@code .gnomish-task/}.
 *
 * <p>Two boundaries are recorded through {@link GitTaskRepository} and cleaned up through {@link
 * TaskWorktreeCleanup} here, both via the shared {@link GitOutcomeRecorder} (task 4.7): {@code
 * Completed} — a normal return from {@link RunnerOutcomeLoop#run} means the pipeline reached its
 * end, and the worktree's last-persisted {@code state.json} — already durably committed by {@link
 * GitAttemptPersistence} — is read back as the final {@link TaskState} rather than threaded
 * through a return value; and {@code Aborted} — {@link RunnerOutcomeLoop#run} throws {@link
 * AbortedException} carrying the full {@link TaskOutcome.Aborted}, which is recorded and then
 * {@link TaskWorktreeCleanup} unconditionally keeps the worktree for forensics (design D6) — the
 * write itself is safe even though durability just broke, since it targets {@code task.json}
 * (the {@code TaskRepository} seam, design D1), a file the broken {@code AttemptPersistence}
 * round never touches.
 *
 * <p>{@code Escalated}/{@code Paused} are never observed here in a fresh run: {@link
 * RunnerOutcomeLoop} loops every one of them back into the engine in-process via its own resume
 * dialogs (see {@link RunnerOutcomeLoop#dispatch}) until a {@code Completed} or {@code Aborted}
 * terminal is reached, so this class's exhaustive-looking "only these two boundaries" is not an
 * oversight — those two are the only outcomes {@link RunnerOutcomeLoop#run} can ever hand back
 * control for. The EOF exceptions ({@link CheckpointEofException}, {@link EscalationEofException},
 * {@link InputExhaustedException}) are deliberately left without a {@code TaskRepository} write:
 * the operator or input stream cut the process off mid-dialog, so the task is left exactly as
 * FR5/NFR-R2 describe a crash — rounds present, no outcome, honestly reported by {@code status}
 * as interrupted.
 *
 * <p>Implements FR6, FR7, UX1, NFR-S2 of add-git-workflow.
 */
final class GitModeRunner {

    private final ManualRunAssembly assembly;
    private final Path worktreesRoot;

    /**
     * @param assembly the shared engine/ports assembly, reused from the in-place path with a
     *     git-backed {@code AttemptPersistence}
     * @param worktreesRoot the root directory under which {@code <project-name>/<taskId>/}
     *     worktrees are created (design D6); production wiring resolves {@code
     *     ~/.gnomish/worktrees}, tests pass a temp directory
     */
    GitModeRunner(ManualRunAssembly assembly, Path worktreesRoot) {
        this.assembly = assembly;
        this.worktreesRoot = worktreesRoot;
    }

    /**
     * Runs one fresh git-mode task to a terminal boundary this class can observe (see class
     * javadoc). Prints the branch/worktree banner before the pipeline runs (UX1).
     *
     * <p>Implements FR6, FR7, UX1 of add-git-workflow.
     *
     * @param cloneDir the {@code --dir} project clone; never mutated (FR7)
     * @param base the {@code --base} override, or {@code null} for the clone's current state
     *     (design D7)
     * @param definition the loaded pipeline the run advances through; never null
     * @param context the synthesized task's identity; never null
     * @param initialState the synthesized task's initial state; never null
     * @param interactiveMode which role(s), if any, use the interactive console adapter
     * @throws UsageException if the task branch already exists or {@code base} does not resolve
     */
    void run(
            Path cloneDir,
            @Nullable String base,
            PipelineDefinition definition,
            TaskContext context,
            TaskState initialState,
            RunArguments.InteractiveMode interactiveMode) {
        GitProcessRunner runner = new GitProcessRunner();
        String taskId = context.taskId();

        new TaskWorktreeCleanup(runner).pruneWorktrees(cloneDir);

        String branchName = branchName(taskId);
        Path worktree = worktreePath(cloneDir, taskId);
        printBanner(branchName, worktree);

        GitTaskRepository taskRepository = new GitTaskRepository(runner, cloneDir, worktreesRoot);
        GitFreshTaskSupport.createTask(taskRepository, taskId, context, base);

        var persistence = new GitAttemptPersistence(runner, worktree, taskId);
        var workspace = new DirectoryWorkspace(worktree);
        var assembled = assembly.assemble(definition, context, initialState, interactiveMode, persistence);

        try {
            assembled.loop().run(definition, context, initialState, workspace, assembled.ports());
        } catch (AbortedException aborted) {
            // aborted.outcome() is never null here: RunnerOutcomeLoop#run always throws the
            // TaskOutcome.Aborted-carrying constructor. The write itself is safe even though
            // durability just broke: it targets task.json (the TaskRepository seam, design D1),
            // a file the broken AttemptPersistence round never touches.
            TaskOutcome.Aborted outcome = aborted.outcome();
            if (outcome != null) {
                GitOutcomeRecorder.recordAndCleanUp(runner, taskRepository, cloneDir, worktree, taskId, outcome);
            }
            throw aborted;
        }

        // A normal return means the pipeline reached Position.PipelineEnd (Completed): the
        // engine's last persist() call already committed that terminal state.json durably, so
        // it is read back here rather than threaded through RunnerOutcomeLoop's void return.
        TaskOutcome.Completed completed = new TaskOutcome.Completed(GitFreshTaskSupport.readFinalState(worktree));
        GitOutcomeRecorder.recordAndCleanUp(runner, taskRepository, cloneDir, worktree, taskId, completed);
    }

    /** The deterministic task branch name (FR2): {@code gnomish/<sanitized taskId>}. */
    private static String branchName(String taskId) {
        return TaskIdSanitizer.branchName(taskId);
    }

    /**
     * The deterministic worktree path (FR6, design D6), delegated to {@link TaskWorktreePath} —
     * computed purely, with no git call, mirroring {@code TaskWorktreeManager#ensureWorktree}'s
     * own path formula exactly so the banner names the same path {@code
     * GitTaskRepository#createTask} materializes. {@code status} (task 5.3) shares the same
     * formula for its worktree-path display.
     */
    private Path worktreePath(Path cloneDir, String taskId) {
        return TaskWorktreePath.resolve(worktreesRoot, cloneDir, taskId);
    }

    /**
     * Prints the branch name and worktree path before the pipeline runs (UX1: "the operator
     * always knows where the work lives").
     */
    private static void printBanner(String branchName, Path worktree) {
        System.out.println("git mode: branch " + branchName);
        System.out.println("git mode: worktree " + worktree);
    }
}
