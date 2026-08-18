package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.Segment;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
 * <p>Implements FR3, FR12, FR21, FR25, D19 of add-sandbox-core.
 */
record ContainerGitModeRunner(
        RunAssembly assembly,
        TaskGit git,
        SandboxProperties sandboxProperties,
        FactoryProperties factoryProperties,
        ContainerSupportFactory supportFactory) {

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
     * @param cloneDir the {@code --dir} project clone; harvest and lifecycle commits land here
     * @param base the {@code --base} override, or {@code null} for the clone's current HEAD
     * @param definition the loaded pipeline the run advances through; never null
     * @param segments the run's container-bound segment plan; never empty
     * @param context the synthesized task's identity; never null
     * @param initialState the synthesized task's initial state; never null
     * @param interactiveMode which role(s), if any, use the interactive console adapter
     * @throws UsageException if the task branch already exists or {@code base} does not resolve
     */
    void run(
            Path cloneDir,
            @Nullable String base,
            PipelineDefinition definition,
            List<Segment> segments,
            TaskContext context,
            TaskState initialState,
            RunArguments.InteractiveMode interactiveMode) {
        String taskId = context.taskId();

        git.branches().harden(cloneDir);
        System.out.println("container mode: branch " + TaskIdSanitizer.branchName(taskId));
        System.out.println("container mode: environment " + TaskIdSanitizer.sanitize(taskId));

        var support = supportFactory.create(
                cloneDir, taskId, segments, sandboxProperties, factoryProperties, definition, List.of());
        GitFreshTaskSupport.createTask(support.taskRepository(), taskId, context, base);

        ContainerTerminalDrive.run(
                assembly, support, definition, context, initialState, interactiveMode, cloneDir, null);
    }
}
