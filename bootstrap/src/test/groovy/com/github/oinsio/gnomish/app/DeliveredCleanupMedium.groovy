package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.ContainerBindingProvider
import com.github.oinsio.gnomish.sandbox.environment.ScriptedSandboxDocker
import java.nio.file.Files
import java.nio.file.Path

/**
 * The execution medium of one delivered-cleanup world (FR4 of fix-terminal-receipt-convergence):
 * which execution mode the take and serve commands select, where a task's lifecycle store lives,
 * and what "the task's workspace is gone" means there. Host: the host-only seam, the registered
 * clone's worktree store, the worktree directory. Container: a selector that plans the container
 * mode daemon-free (an image, a runtime probe that answers yes), a support whose boxes run on a
 * {@link ScriptedSandboxDocker} and whose bare-object store pushes like production's, and the box
 * removal the scripted docker records.
 */
final class DeliveredCleanupMedium {

    static final String HOST = 'host'
    static final String CONTAINER = 'container'

    private static final SandboxProperties SANDBOX =
    new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)

    final String name
    final ScriptedSandboxDocker docker = new ScriptedSandboxDocker()
    private final TaskGit git
    private final Path guard

    /**
     * @param name {@link #HOST} or {@link #CONTAINER}
     * @param git the world's bundle, whose tenure book a container support stamps from
     * @param guard the directory the scripted guard's files live in
     */
    DeliveredCleanupMedium(String name, TaskGit git, Path guard) {
        this.name = name
        this.git = git
        this.guard = guard
    }

    /** The pinned pipeline a {@code run --resume} hands its runner: the one stage the project declares. */
    static PipelineDefinition definition() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'build it', [], [],
            new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
            'stages/build/instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
    }

    /** The one container segment every stage runs in, as the selector plans it. */
    static List<Segment> segments() {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), definition().stages())
        ]
    }

    boolean isHost() {
        name == HOST
    }

    /** The execution-mode seam the take and serve commands are built with. */
    ContainerTakeSupport containerTakeSupport() {
        if (host) {
            return ContainerTakeSupportFixture.hostOnly()
        }
        def registry = AdapterBindingRegistry.ratified([
            new ContainerBindingProvider()
        ], BindingTrustTable.firstParty())
        def selector = new SandboxModeSelector(new BindingProperties(BindingNames.CONTAINER, [:]), SANDBOX, registry,
        { true } as ContainerRuntimeProbe)
        new ContainerTakeSupport(selector, supportFactory())
    }

    /** The per-task container support: boxes on the scripted docker, stamps from the bundle's book. */
    ContainerSupportFactory supportFactory() {
        { Path c, String t, List<Segment> s, d, List<String> creds ->
            new ContainerRunSupport(new GitProcessRunner(), c, t,
            docker.environments(TaskIdSanitizer.sanitize(t), c, SANDBOX, guard), s,
            SandboxLifecyclePass.NONE, git.epochs())
        } as ContainerSupportFactory
    }

    /** The production lifecycle store of {@code taskId} in this medium, push decorator included. */
    TaskLifecycleStore store(RegisteredClone clone, String taskId) {
        host
                ? git.store().taskRepository(clone)
                : supportFactory().create(clone.clonePath(), taskId, segments(), definition(), []).taskRepository()
    }

    /** The task's workspace is gone: the worktree directory, or the box the scripted docker removed. */
    boolean workspaceGone(RegisteredClone clone, String taskId) {
        String key = TaskIdSanitizer.sanitize(taskId)
        host
                ? !Files.exists(clone.worktrees().resolve(key))
                : docker.runs.any {
                    it[0] == 'rm' && it.any { arg -> arg.contains(key) }
                }
    }
}
