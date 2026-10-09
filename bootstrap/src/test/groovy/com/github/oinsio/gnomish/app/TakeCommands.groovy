package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import java.time.Clock

/**
 * Builds a {@link TakeCommand} for the take specs the way the composition root does — through the
 * production {@link SlotWiringFactory}, over the {@link TrackerWiring}'s own pipeline source — so a
 * spec cannot assemble a wiring production never builds (testing.md, "Fixtures assemble through
 * production owners"). Replaces the production-side {@code TakeCommandFactory} that design D7 of
 * collapse-composition-roots removed; the argument order is the one that factory had. {@code clone}
 * is the registered clone the loader would resolve for the spec's {@code --dir}: the slots work in
 * it, the command's {@link ProjectScope} hands it to the parser and names the project in the minted
 * instance id (FR3, FR9, FR10 of add-project-registry). {@code clock} reaches the command through
 * its seams.
 */
final class TakeCommands {

    private TakeCommands() {}

    /** All seams at {@link TakeCommandSeams#defaults}. */
    static TakeCommand of(RunAssembly assembly, TaskGit git, RegisteredClone clone, String taskIdMdcKey,
            FactoryProperties factoryProperties, Clock clock, TrackerWiring trackerWiring,
            SandboxLifecyclePass sandboxLifecyclePass, ContainerTakeSupport containerTakeSupport) {
        of(assembly, git, clone, taskIdMdcKey, factoryProperties, clock, trackerWiring,
                TakeCommandSeams.defaults(new VirtualClock()), sandboxLifecyclePass, containerTakeSupport)
    }

    static TakeCommand of(RunAssembly assembly, TaskGit git, RegisteredClone clone, String taskIdMdcKey,
            FactoryProperties factoryProperties, Clock clock, TrackerWiring trackerWiring, TakeCommandSeams seams,
            SandboxLifecyclePass sandboxLifecyclePass, ContainerTakeSupport containerTakeSupport) {
        def resolvedClone = RegisteredCloneFixture.provider(clone)
        def slotWiringFactory = new SlotWiringFactory(
                assembly, resolvedClone, taskIdMdcKey, clock, containerTakeSupport,
                trackerWiring.pipelineSource(), VirtualTimeRetries.terminalWrite())
        new TakeCommand(slotWiringFactory, git, factoryProperties, new ProjectScope(resolvedClone, factoryProperties),
                trackerWiring, seams.withClock(clock), sandboxLifecyclePass)
    }
}
