package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.git.TaskWorktreePath;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.BasePin;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import java.util.List;

/**
 * Drives the fresh-run git-mode path (design D8 of add-git-workflow, task 4.4): the {@code
 * --mode=git} counterpart of {@link RunAssembly}'s in-place wiring, wired for a brand-new
 * task only — {@code --resume} is task 4.6 onward and is not handled here.
 *
 * <p>Sequence, per FR6/FR7/UX1: prune stale worktree registrations, print the deterministic
 * branch name and worktree path <em>before</em> anything else runs (UX1 — "the operator always
 * knows where the work lives"; both names are pure functions of the registered clone and {@code taskId},
 * computed without any git call, see {@link #branchName}/{@link #worktreePath}), then record task
 * start via {@code GitTaskRepository#createTask} — the single call that actually creates the
 * branch off {@code --base} (or the clone's current {@code HEAD} when absent, design D7) and
 * materializes the worktree — then assemble {@code EnginePorts} with a fresh {@code
 * GitAttemptPersistence} rooted at the worktree — not {@code --dir} — as the workspace (FR7: "the
 * clone itself is untouched"). {@code GitTaskRepository#createTask} is deliberately the only
 * caller of branch/worktree creation in this sequence: a second, independent creation attempt
 * here would race it and see a spurious "already exists" on its own first call.
 *
 * <p>{@code GitTaskRepository#createTask} throws {@link GitTaskRepositoryException} for both an
 * already-existing branch and an unresolved {@code --base}; {@link #run} remaps that exception to
 * a {@link UsageException} (exit code 2) here: this is a fresh run, not a resume, so either
 * condition names an operator mistake the run cannot proceed past, not a resumable one.
 *
 * <p>Task 4.5 (NFR-S2, workspace hygiene): pointing the workspace at the worktree here does not,
 * by itself, risk decision-file temp dirs or logs entering the round commit. Both are already
 * anchored outside any workspace root by construction, unchanged by this class: {@code
 * CliStageExecutor}'s {@code DecisionFileTransport} always roots each round's temp directory
 * under {@code java.io.tmpdir} (never under the {@link DirectoryWorkspace} it is handed), and
 * {@code logback-spring.xml} writes the instance's rolling log file under the factory home's {@code
 * projects/<name>/logs/} — both independent of {@code --dir}/worktree/workspace entirely. See {@code
 * GitModeWorkspaceHygieneSpec} for the regression proof: a real round's commit tree contains only
 * the gnome's own change and {@code .gnomish-task/}.
 *
 * <p>Every terminal boundary is recorded through {@code GitTaskRepository} and cleaned up through
 * {@code TaskWorktreeCleanup}, via the shared {@link GitOutcomeRecorder} (task 4.7): {@code
 * Completed} — the worktree's last-persisted {@code state.json}, already durably committed by
 * {@code GitAttemptPersistence}, is read back as the final {@link TaskState}; {@code Aborted} —
 * {@link RunnerOutcomeLoop#run} throws {@link AbortedException} carrying the full {@link
 * TaskOutcome.Aborted}, which is recorded and the worktree unconditionally kept for forensics
 * (design D6) — the write is safe even though durability just broke, since it targets {@code
 * task.json} (the {@code TaskRepository} seam, design D1), a file the broken {@code
 * AttemptPersistence} round never touches; and a stop — {@code Escalated} or {@code Paused},
 * returned by the loop — is recorded as a park with the worktree kept for the resume, then the run
 * exits through {@link RunParkedException} (design D8 of make-run-headless).
 *
 * <p>Kept in sync with {@link ContainerGitModeRunner}: both run the SAME manual fresh-run recipe
 * — harden the clone's branches, print the banner naming where the work lives (UX1), bind and peel
 * the law through {@code ManualRunLawBinding#bind}, resolve the {@code --base} override through
 * {@code GitFreshTaskSupport#resolveManualBase} and create the task through {@code
 * GitFreshTaskSupport#createTask} FROM THAT LAW COMMIT (FR15, D12 revised 2026-09-10), then drive
 * the engine under the same binding — and both settle the {@code Completed}, {@code Aborted} and park
 * terminals through the mode's own outcome/cleanup ordering: both record a park outcome at the
 * terminal boundary and keep the workspace for the resume (design D6 of make-run-headless). The
 * media differ (host worktree here, task environment there); the recipe and its order must not.
 *
 * <p>Implements FR6, FR7, UX1, NFR-S2 of add-git-workflow; FR9 of add-project-registry; FR1, FR2, FR10
 * of make-run-headless.
 *
 * @param assembly the shared engine/ports assembly, reused from the in-place path with a
 *     git-backed {@code AttemptPersistence}
 * @param console the console owner the banner is written through (FR5, FR6 of
 *     harden-untrusted-text-sinks)
 * @param registeredClone the registered clone whose own worktree folder the task worktree is created in
 *     (design D6; FR9 of add-project-registry)
 */
record GitModeRunner(RunAssembly assembly, TaskGit git, RegisteredClone registeredClone, ConsoleIO console) {

    /**
     * Runs one fresh git-mode task to a terminal boundary this class can observe (see class
     * javadoc). Prints the branch/worktree banner before the pipeline runs (UX1).
     *
     * <p>Implements FR6, FR7, UX1 of add-git-workflow.
     *
     * @param order the run order: the {@code --dir} project clone (never mutated, FR7), the {@code
     *     --base} override ({@code null} for the clone's current state, design D7), the loaded
     *     pipeline and the console mode; its {@code discardWork} is meaningless on a fresh run
     * @param context the synthesized task's identity; never null
     * @param initialState the synthesized task's initial state; never null
     * @throws UsageException if the task branch already exists or {@code base} does not resolve
     */
    void run(RunOrder order, TaskContext context, TaskState initialState) {
        String taskId = context.taskId();
        Path cloneDir = order.cloneDir();
        PipelineDefinition definition = order.definition();

        git.worktrees().pruneWorktrees(cloneDir);
        git.branches().harden(cloneDir);

        String branchName = branchName(taskId);
        Path worktree = worktreePath(taskId);
        printBanner(branchName, worktree);

        var taskRepository = git.store().taskRepository(registeredClone);
        // FR15, D12 of add-base-ref-resolution (revised 2026-09-10): the law is bound and peeled
        // first, and the branch starts at that very commit — the manual tier's own way of keeping a
        // base name out of the repository port.
        var law = ManualRunLawBinding.bind(assembly, cloneDir, order.base());
        var baseDecision = GitFreshTaskSupport.resolveManualBase(order.base());
        GitFreshTaskSupport.createTask(
                taskRepository,
                taskId,
                context,
                law.lawCommit(),
                new BasePin(baseDecision.ref(), null, baseDecision.rule()),
                initialState);

        var persistence = git.store().attemptPersistence(worktree, taskId);
        var workspace = new DirectoryWorkspace(worktree);
        // The host half of the mid-round push (FR1, FR3, design D3 of wire-host-mid-round-push):
        // this runner is git-mode by type, so attaching here needs no flag; in-place mode never
        // reaches this line and keeps the assembly's identity default.
        var assembled = assembly.withHostGitPush(git.midRoundPush())
                .assemble(order, context, initialState, persistence, List.of(), law.binding());

        var returnPath = new TerminalOutcomeRender.ReturnPath(cloneDir, taskId);
        TaskOutcome outcome;
        try {
            outcome = assembled.loop().run(definition, context, initialState, workspace, assembled.ports(), returnPath);
        } catch (AbortedException aborted) {
            // aborted.outcome() is never null here: RunnerOutcomeLoop#run always throws the
            // TaskOutcome.Aborted-carrying constructor. The write itself is safe even though
            // durability just broke: it targets task.json (the TaskRepository seam, design D1),
            // a file the broken AttemptPersistence round never touches.
            TaskOutcome.Aborted abortedOutcome = aborted.outcome();
            if (abortedOutcome != null) {
                GitOutcomeRecorder.recordAndCleanUp(git, taskRepository, cloneDir, worktree, taskId, abortedOutcome);
            }
            throw aborted;
        }

        if (outcome instanceof TaskOutcome.Completed) {
            // The pipeline reached Position.PipelineEnd: the engine's last persist() call already
            // committed that terminal state.json durably, so it is read back from the medium the
            // recovery paths read rather than taken from the in-memory outcome.
            TaskOutcome.Completed completed = new TaskOutcome.Completed(
                    git.store().readRecordedState(worktree).orElseThrow(() -> AbsentEnvelope.state(taskId, worktree)));
            GitOutcomeRecorder.recordAndCleanUp(git, taskRepository, cloneDir, worktree, taskId, completed);
            return;
        }
        // A stop (Escalated/Paused, design D8 of make-run-headless): the park is recorded on the
        // branch — the worktree kept by the outcome-driven disposal — before the process exits 10/11.
        GitOutcomeRecorder.recordAndCleanUp(git, taskRepository, cloneDir, worktree, taskId, outcome);
        throw new RunParkedException(outcome, returnPath);
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
    private Path worktreePath(String taskId) {
        return TaskWorktreePath.resolve(registeredClone, taskId);
    }

    /**
     * Prints the branch name and worktree path before the pipeline runs (UX1: "the operator
     * always knows where the work lives").
     */
    private void printBanner(String branchName, Path worktree) {
        console.print("git mode: branch " + branchName + ConsoleIO.LINE_END);
        console.print("git mode: worktree " + worktree + ConsoleIO.LINE_END);
    }
}
