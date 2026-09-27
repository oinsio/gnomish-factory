package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import java.nio.file.Path
import java.time.Clock

/**
 * Builds a {@link TakeCommand} for the take specs the way the composition root does — through the
 * production {@link SlotWiringFactory}, over the {@link TrackerWiring}'s own pipeline source — so a
 * spec cannot assemble a wiring production never builds (testing.md, "Fixtures assemble through
 * production owners"). Replaces the production-side {@code TakeCommandFactory} that design D7 of
 * collapse-composition-roots removed; the argument order is the one that factory had.
 */
final class TakeCommands {

    private TakeCommands() {}

    /** All seams at {@link TakeCommandSeams#DEFAULTS}. */
    static TakeCommand of(RunAssembly assembly, TaskGit git, Path worktreesRoot, String taskIdMdcKey,
            FactoryProperties factoryProperties, Clock clock, TrackerWiring trackerWiring,
            SandboxLifecyclePass sandboxLifecyclePass, ContainerTakeSupport containerTakeSupport) {
        of(assembly, git, worktreesRoot, taskIdMdcKey, factoryProperties, clock, trackerWiring,
                TakeCommandSeams.DEFAULTS, sandboxLifecyclePass, containerTakeSupport)
    }

    static TakeCommand of(RunAssembly assembly, TaskGit git, Path worktreesRoot, String taskIdMdcKey,
            FactoryProperties factoryProperties, Clock clock, TrackerWiring trackerWiring, TakeCommandSeams seams,
            SandboxLifecyclePass sandboxLifecyclePass, ContainerTakeSupport containerTakeSupport) {
        def slotWiringFactory = new SlotWiringFactory(
                assembly, worktreesRoot, taskIdMdcKey, clock, containerTakeSupport, trackerWiring.pipelineSource())
        new TakeCommand(slotWiringFactory, git, factoryProperties, clock, trackerWiring, seams, sandboxLifecyclePass)
    }
}
