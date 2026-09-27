package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import java.time.Clock

/**
 * Builds a {@link ServeCommand} for the serve specs the way the composition root does — through
 * the production {@link SlotWiringFactory}, {@link ServeAssembly} and {@link ServeRuntimeAssembly}
 * — so a spec cannot assemble a daemon production never builds (testing.md, "Fixtures assemble
 * through production owners"). The argument order is the one the command's constructor had before
 * design D7 of collapse-composition-roots moved its fixed equipment into the runtime assembly.
 */
final class ServeCommands {

    private ServeCommands() {}

    static ServeCommand of(RunAssembly assembly, TaskGit git, FactoryPaths paths, String taskIdMdcKey,
            FactoryProperties factoryProperties, ServeProperties serveProperties, Clock clock,
            com.github.oinsio.gnomish.domain.engine.port.Clock feedClock, TrackerWiring trackerWiring,
            FeedAutomatonStarter starter, SandboxLifecyclePass sandboxLifecyclePass,
            ContainerTakeSupport containerTakeSupport, ConsoleIO errorConsole) {
        def slotWiringFactory = new SlotWiringFactory(
                assembly, paths.worktreesRoot(), taskIdMdcKey, clock, containerTakeSupport, trackerWiring.pipelineSource())
        def runtimeAssembly = new ServeRuntimeAssembly(
                slotWiringFactory,
                new ServeAssembly(factoryProperties, serveProperties, feedClock),
                git,
                paths,
                clock,
                sandboxLifecyclePass,
                containerTakeSupport)
        new ServeCommand(runtimeAssembly, git, factoryProperties, serveProperties, trackerWiring, starter, errorConsole)
    }
}
