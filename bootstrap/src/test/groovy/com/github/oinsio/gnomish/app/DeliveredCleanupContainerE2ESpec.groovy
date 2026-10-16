package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.environment.ContainerBindingProvider
import com.github.oinsio.gnomish.sandbox.environment.DockerRuntimeProbe
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The seventh consumer of {@code DeliveredCleanup} (design D5 of fix-terminal-receipt-convergence):
 * {@code TakeContainerEngineExecution}'s fresh finish. A fresh container {@code take} must close a
 * round in a real box before it can finish, which no scripted docker does, so this one row of the
 * identity spec ({@link DeliveredCleanupIdentitySpec}) runs on a real Docker daemon: the fake agent
 * in the sandbox image plays the gnome, the round is harvested, the tracker finish lands, and the
 * cleanup — through the one owner — follows it.
 *
 * <p>Asserted as the identity spec asserts every other row: the tip is {@code Delivered} with one
 * cleanup commit on top of the {@code Completed} outcome, the task's box, volume and network are
 * gone, the bare {@code origin} carries the cleanup and its register, the tracker reports the task
 * finished; a second {@code take} of the finished task writes nothing (NFR-R1).
 *
 * <p>Docker- and guard-image-gated, like {@link TakeContainerLifecycleE2ESpec}: skips cleanly with
 * no daemon. FR4, NFR-R1 of fix-terminal-receipt-convergence.
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class DeliveredCleanupContainerE2ESpec extends Specification implements AppAssemblyFixture, ApplicationArgumentsFixture {

    @TempDir
    Path tempDir

    def cleanup() {
        ContainerE2eDocker.removeTaskObjects(DeliveredCleanupWorld.TASK_ID)
    }

    def "FR4, NFR-R1: TakeContainerEngineExecution on a real box converges the delivered branch once"() {
        given: 'a Ready task and a project bound to the container adapter, over the fake agent image'
        def world = new DeliveredCleanupWorld(tempDir, DeliveredCleanupMedium.CONTAINER)
        world.trackerReady()

        when: 'take runs the task fresh, in a real box, to its finish'
        int exit = take(world)

        then: 'it is delivered, and the tip is Delivered: one cleanup commit on top of the Completed outcome'
        exit == 0
        world.shape() == 'Delivered'
        world.subjects().count(ServiceCommitMessages.cleanup()) == 1
        world.subjects().last() == ServiceCommitMessages.cleanup()

        and: "the task's box, volume and network are gone, and origin carries the cleanup and its register"
        ContainerE2eDocker.taskObjects(DeliveredCleanupWorld.TASK_ID).isEmpty()
        world.originCarriesCleanup()

        and: 'the tracker reports the task finished, under the one claim the fresh take wrote'
        world.tracker.fetchTask(DeliveredCleanupWorld.REF).state() instanceof TrackerTaskState.Finished
        world.claims() == 1

        when: 'the finished task is taken again'
        def before = world.footprint()
        take(world)

        then: 'it writes nothing'
        world.footprint() == before
        ContainerE2eDocker.taskObjects(DeliveredCleanupWorld.TASK_ID).isEmpty()
    }

    /** {@code take <ref>} over the real container dispatch the composition root binds; the exit code. */
    private int take(DeliveredCleanupWorld world) {
        def agent = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def sandbox = new SandboxProperties(FakeAgentSandboxImage.ensureBuilt('plain-round'),
                null, null, null, [], [], false, null, null, null, null)
        def registry = AdapterBindingRegistry.ratified([
            new ContainerBindingProvider()
        ], BindingTrustTable.firstParty())
        def selector = new SandboxModeSelector(new BindingProperties(BindingNames.CONTAINER, [:]), sandbox, registry,
        DockerRuntimeProbe.&dockerAvailable as ContainerRuntimeProbe)
        def containers = new ContainerTakeSupport(selector, ContainerSupportFixture.tracked(world.git.epochs(), sandbox, agent))
        def wiring = new TrackerWiring([github: fakeFactory(world.tracker)], MapSecretsProvider.NONE,
        TrackerValidatorStub.acceptingGithubSource(), VirtualTimeEquipment.create())
        // Real time, the root's: the container support's boxes run on it, so the slot does too.
        def command = TakeCommands.of(newAssembly(agent), world.git, world.clone, 'taskId', agent, wiring,
                SandboxLifecyclePass.NONE, containers)
        runExitCode(command, args('take', DeliveredCleanupWorld.TASK_ID, "--dir=${world.projectDir}"))
    }
}
