package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.BasePin;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.Segment;
import java.nio.file.Path;
import java.util.List;

/**
 * The container-mode counterpart of {@link GitModeRunner} (the integration
 * pass of add-sandbox-core): a fresh git-mode task whose working copy lives
 * inside a task container instead of a host worktree. The task branch is
 * created with {@code task.json} factory-side over bare git objects (FR25,
 * D19) — no worktree ever exists; the environment materializes lazily on the
 * first round through the {@code ContainerRunSupport} lease, rounds close per
 * the snapshot-first protocol (FR21), and the terminal boundary follows D19's
 * ordering: Completed disposes the environment before the outcome and cleanup
 * commits; every non-completed exit keeps the environment stopped with volume
 * and network retained.
 *
 * <p>Kept in sync with {@link GitModeRunner}: both run the SAME manual fresh-run recipe — harden
 * the clone's branches, print the banner naming where the work lives (UX1), bind and peel the law
 * through {@code ManualRunLawBinding#bind}, resolve the {@code --base} override through {@code
 * GitFreshTaskSupport#resolveManualBase} and create the task through {@code
 * GitFreshTaskSupport#createTask} FROM THAT LAW COMMIT (FR15, D12 revised 2026-09-10), then drive
 * the engine under the same binding — and both observe only the {@code Completed} and
 * {@code Aborted} terminals, recording each through the mode's own outcome/cleanup ordering. The
 * media differ (host worktree there, task environment here); the recipe and its order must not.
 *
 * <p>Implements FR3, FR12, FR21, FR25, D19 of add-sandbox-core.
 */
record ContainerGitModeRunner(
        RunAssembly assembly,
        TaskGit git,
        SandboxProperties sandboxProperties,
        FactoryProperties factoryProperties,
        ContainerSupportFactory supportFactory,
        ConsoleIO console) {

    /**
     * The support factory is injected ({@link ContainerSupportFactory}, mirroring {@link
     * ContainerResumeRunner}): the composition root binds the real container bundle, daemon-free
     * specs bind one whose environments run over a scripted fake docker CLI, so the fresh-run path
     * — including its runner-start orphan sweep (FR11) — is exercised without a daemon.
     */
    ContainerGitModeRunner {}

    /**
     * Runs one fresh container-mode task to a terminal boundary (mirroring {@link
     * GitModeRunner#run}'s outcome handling; see that class's javadoc for why only {@code
     * Completed} and {@code Aborted} can reach this frame).
     *
     * @param order the run order: the {@code --dir} project clone (harvest and lifecycle commits
     *     land here), the {@code --base} override ({@code null} for the clone's current HEAD), the
     *     loaded pipeline and the console mode; its {@code discardWork} is meaningless on a fresh run
     * @param segments the run's container-bound segment plan; never empty
     * @param context the synthesized task's identity; never null
     * @param initialState the synthesized task's initial state; never null
     * @throws UsageException if the task branch already exists or {@code base} does not resolve
     */
    void run(RunOrder order, List<Segment> segments, TaskContext context, TaskState initialState) {
        String taskId = context.taskId();
        Path cloneDir = order.cloneDir();

        git.branches().harden(cloneDir);
        console.print("container mode: branch " + TaskIdSanitizer.branchName(taskId) + ConsoleIO.LINE_END);
        console.print("container mode: environment " + TaskIdSanitizer.sanitize(taskId) + ConsoleIO.LINE_END);

        var support = supportFactory.create(
                cloneDir, taskId, segments, sandboxProperties, factoryProperties, order.definition(), List.of());
        // FR15, D12 of add-base-ref-resolution (revised 2026-09-10): the law is bound and peeled
        // first, and the branch starts at that very commit — the manual tier's own way of keeping a
        // base name out of the repository port.
        var law = ManualRunLawBinding.bind(assembly, cloneDir, order.base());
        var baseDecision = GitFreshTaskSupport.resolveManualBase(order.base());
        GitFreshTaskSupport.createTask(
                support.taskRepository(),
                taskId,
                context,
                law.lawCommit(),
                new BasePin(baseDecision.ref(), null, baseDecision.rule()),
                initialState);

        ContainerTerminalDrive.run(assembly, support, order, context, initialState, law.binding(), null);
    }
}
