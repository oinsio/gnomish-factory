package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import java.time.Clock

/**
 * Builds a {@link ServeCommand} for the serve specs the way the composition root does — through
 * the production {@link SlotWiringFactory}, {@link ServeAssembly} and {@link ServeRuntimeAssembly}
 * — so a spec cannot assemble a daemon production never builds (testing.md, "Fixtures assemble
 * through production owners"). The argument order is the one the command's constructor had before
 * design D7 of collapse-composition-roots moved its fixed equipment into the runtime assembly;
 * {@code clone} is the registered clone the loader would resolve: the slots work in it and the
 * janitor sweeps its worktree folder (FR9), and its project's serve directory is where the daemon
 * writes its observability files (FR10 of add-project-registry).
 */
final class ServeCommands {

    private ServeCommands() {}

    static ServeCommand of(RunAssembly assembly, TaskGit git, RegisteredClone clone, String taskIdMdcKey,
            FactoryProperties factoryProperties, ServeProperties serveProperties, Clock clock,
            java.time.InstantSource feedClock, TrackerWiring trackerWiring,
            FeedAutomatonStarter starter, SandboxLifecyclePass sandboxLifecyclePass,
            ContainerTakeSupport containerTakeSupport, ConsoleIO errorConsole) {
        def resolvedClone = RegisteredCloneFixture.provider(clone)
        def slotWiringFactory = new SlotWiringFactory(
                assembly, resolvedClone, taskIdMdcKey, clock, containerTakeSupport, trackerWiring.pipelineSource(),
                VirtualTimeRetries.terminalWrite())
        def runtimeAssembly = new ServeRuntimeAssembly(
                slotWiringFactory,
                new ServeAssembly(factoryProperties, serveProperties, feedClock, resolvedClone),
                git,
                clock,
                sandboxLifecyclePass,
                containerTakeSupport.sandboxProperties())
        new ServeCommand(runtimeAssembly, git, new ProjectScope(resolvedClone, factoryProperties), serveProperties,
                trackerWiring, starter, errorConsole)
    }
}
