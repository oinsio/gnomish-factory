package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import java.time.Instant

/**
 * Builds a {@link TakeCommand} for the take specs the way the composition root does — through the
 * production {@link SlotWiringFactory}, over the {@link TrackerWiring}'s own pipeline source — so a
 * spec cannot assemble a wiring production never builds (testing.md, "Fixtures assemble through
 * production owners"). Replaces the production-side {@code TakeCommandFactory} that design D7 of
 * collapse-composition-roots removed. {@code clone} is the registered clone the loader would
 * resolve for the spec's {@code --dir}: the slots work in it, the command's {@link ProjectScope}
 * hands it to the parser and names the project in the minted instance id (FR3, FR9, FR10 of
 * add-project-registry).
 *
 * <p>One time per graph, as the root hands its one bean to both (task 3.9 of
 * supervise-daemon-loops-and-embed-dashboard): the spec chooses the graph's time once, on the
 * assembly ({@link #slotTime}); the slot derives its abort stamp, its terminal-write retry and its
 * "now" from it, and the command's seams are built on the same clock ({@link #beatTime}). No
 * argument here takes a second clock.
 */
final class TakeCommands {

    private TakeCommands() {}

    /** The instant the take specs' graphs stand at unless a spec chooses its own. */
    static final Instant START = Instant.parse('2026-01-01T00:00:00Z')

    /**
     * The graph time a take spec builds its assembly on: a virtual equipment standing at {@code
     * start}, so every stamp of the slot reads {@code start} and a terminal write against a tracker
     * outage exhausts its production bound in virtual time.
     */
    static TimeEquipment slotTime(Instant start = START) {
        VirtualTimeEquipment.on(new VirtualClock(start))
    }

    /**
     * The seams' time over {@code assembly}'s clock: the clock every stamp of the command reads, and
     * the real sleeper the heartbeat and the standing reaper wait on in their background threads. The
     * one place in the take fixtures that builds the real sleeper (FR21 of
     * supervise-daemon-loops-and-embed-dashboard).
     */
    static TimeEquipment beatTime(RunAssembly assembly) {
        new TimeEquipment(assembly.timeEquipment().clock(), realSleeper())
    }

    /** The real sleeper the take specs' background beats wait on; see {@link #beatTime}. */
    static Sleeper realSleeper() {
        // real-time-wiring: every take spec runs a real fake-agent subprocess and real git, which
        //     finish on the wall clock, and the heartbeat and the standing reaper wait beside that
        //     run on their own threads: a virtual sleeper returns at once, so they would spin for
        //     the whole round. The lifecycle specs (InMemoryTakeHeartbeatLifecycleSpec,
        //     InMemoryTakeDeathAndRecoverySpec, TakeCommandStandingReaperWiringSpec) also observe
        //     the beats on that real cadence while the round is in flight.
        new ThreadSleeper()
    }

    /** All seams at {@link TakeCommandSeams#defaults}, on {@link #beatTime} of {@code assembly}. */
    static TakeCommand of(RunAssembly assembly, TaskGit git, RegisteredClone clone, String taskIdMdcKey,
            FactoryProperties factoryProperties, TrackerWiring trackerWiring,
            SandboxLifecyclePass sandboxLifecyclePass, ContainerTakeSupport containerTakeSupport) {
        of(assembly, git, clone, taskIdMdcKey, factoryProperties, trackerWiring, { TimeEquipment time ->
            TakeCommandSeams.defaults(time)
        }, sandboxLifecyclePass, containerTakeSupport)
    }

    /**
     * {@code seams} receives {@link #beatTime} of {@code assembly} and layers on the seams the spec
     * overrides, so the command's seams cannot stand on a clock other than the slot's.
     */
    static TakeCommand of(RunAssembly assembly, TaskGit git, RegisteredClone clone, String taskIdMdcKey,
            FactoryProperties factoryProperties, TrackerWiring trackerWiring, Closure<TakeCommandSeams> seams,
            SandboxLifecyclePass sandboxLifecyclePass, ContainerTakeSupport containerTakeSupport) {
        def resolvedClone = RegisteredCloneFixture.provider(clone)
        def slotWiringFactory = new SlotWiringFactory(
                assembly, resolvedClone, taskIdMdcKey, containerTakeSupport, trackerWiring.pipelineSource())
        new TakeCommand(slotWiringFactory, git, factoryProperties, new ProjectScope(resolvedClone, factoryProperties),
                trackerWiring, seams.call(beatTime(assembly)), sandboxLifecyclePass)
    }
}
