package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.sandbox.SandboxProperties

/**
 * Builds a {@link ServeCommand} for the serve specs the way the composition root does — through
 * the production {@link SlotWiringFactory}, {@link ServeAssembly} and {@link ServeRuntimeAssembly}
 * — so a spec cannot assemble a daemon production never builds (testing.md, "Fixtures assemble
 * through production owners"). The argument order is the one the command's constructor had before
 * design D7 of collapse-composition-roots moved its fixed equipment into the runtime assembly;
 * {@code clone} is the registered clone the loader would resolve: the slots work in it and the
 * janitor sweeps its worktree folder (FR9), and its project's serve directory is where the daemon
 * writes its observability files (FR10 of add-project-registry).
 *
 * <p>One time per daemon, as the root hands its one bean to both (task 3.9 of
 * supervise-daemon-loops-and-embed-dashboard): the daemon's equipment is {@code assembly}'s, which
 * the slot wiring derives its own time from as well, so no argument here takes a second one. A spec
 * whose serve threads must really wait builds its assembly on the root's own equipment (the {@link
 * AppAssemblyFixture#newAssembly} default); the rest build it on a virtual one.
 */
final class ServeCommands {

    private ServeCommands() {}

    static ServeCommand of(RunAssembly assembly, TaskGit git, RegisteredClone clone, String taskIdMdcKey,
            FactoryProperties factoryProperties, ServeProperties serveProperties, TrackerWiring trackerWiring,
            FeedAutomatonStarter starter, SandboxLifecyclePass sandboxLifecyclePass,
            ContainerTakeSupport containerTakeSupport, ConsoleIO errorConsole) {
        def resolvedClone = RegisteredCloneFixture.provider(clone)
        def scope = new ProjectScope(resolvedClone, factoryProperties)
        def slotWiringFactory = new SlotWiringFactory(
                assembly, resolvedClone, taskIdMdcKey, containerTakeSupport, trackerWiring.pipelineSource())
        def runtimeAssembly = new ServeRuntimeAssembly(
                slotWiringFactory,
                // One time equipment for the whole daemon, the assembly's, as the root hands its one
                // bean to both (design D20 of supervise-daemon-loops-and-embed-dashboard).
                new ServeAssembly(factoryProperties, serveProperties, assembly.timeEquipment(), resolvedClone),
                git,
                sandboxLifecyclePass,
                // The host-only sandbox config every caller's bundle was built with: the bundle no
                // longer exposes its inputs (design D22 of supervise-daemon-loops-and-embed-dashboard).
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                // The page inside serve, as the root builds it: the tracker wiring in its BoardReaders
                // role, over the command's own project scope and the daemon's one time equipment.
                new ServeDashboard(trackerWiring, scope, factoryProperties, assembly.timeEquipment()))
        new ServeCommand(runtimeAssembly, git, scope, serveProperties, trackerWiring, starter, errorConsole)
    }
}
