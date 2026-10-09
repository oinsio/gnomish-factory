package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.sandbox.DiscoveredBindings
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR20 of make-checkpoint-gate-durable (design D12): the production {@link ContainerSupports}
 * builds each ownership mode's {@link ContainerRunSupportFactory} with the installation's half —
 * the two property sets, the environment factory and the sandbox lifecycle pass — once, and every
 * run of that mode shares it; the runs pass only their own five per-run values.
 *
 * <p>Daemon-free by construction: building a run's support resolves the project identity through
 * {@code git remote} and assembles the environments seam, but runs no Docker command.
 */
class ContainerSupportsSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    Path cloneDir

    SandboxProperties sandbox = new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)

    TimeEquipment time = VirtualTimeEquipment.create()

    SandboxModeSelector selector = new SandboxModeSelector(
    new BindingProperties(null, [:]), sandbox, DiscoveredBindings.real(), {
        -> true
    } as ContainerRuntimeProbe)

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('a.txt'), 'seed\n')
        commitAll(cloneDir)
    }

    // FR20: the lifecycle pass is built with the mode's factory, not per run — two runs of one mode
    // sweep through the very same pass.
    def "two runs of one mode share the mode's sandbox lifecycle pass"() {
        given:
        def manual = newSupports().manualSupport()

        when:
        def first = (ContainerRunSupport) manual.create(cloneDir, 'T-1', segments(), pipeline(), [])
        def second = (ContainerRunSupport) manual.create(cloneDir, 'T-2', segments(), pipeline(), [])

        then:
        first.sandboxLifecyclePass.is(second.sandboxLifecyclePass)
        first.sandboxLifecyclePass.is(((ContainerRunSupportFactory) manual).sandboxLifecyclePass())
    }

    // FR19, FR20: one environment factory per mode, holding the installation's own settings, and
    // each mode's factory stamps its own label on the objects its runs create.
    def "each mode holds its own environment factory over the installation's settings and stamps its own label"() {
        given:
        def supports = newSupports()

        when:
        def manual = (ContainerRunSupportFactory) supports.manualSupport()
        def tracked = (ContainerRunSupportFactory) supports.takeSupport().containerSupportFactory()

        then: 'the installation settings are the ones the supports were given, held by each mode'
        manual.sandboxProperties().is(sandbox)
        tracked.sandboxProperties().is(sandbox)
        manual.factoryProperties().is(tracked.factoryProperties())

        and: 'each mode built its own box equipment'
        !manual.environments().is(tracked.environments())
        manual.ownershipMode() == OwnershipMode.MANUAL
        tracked.ownershipMode() == OwnershipMode.TRACKED

        and: 'a run of each mode stamps that mode'
        ((ContainerRunSupport) manual.create(cloneDir, 'T-m', segments(), pipeline(), []))
        .environments.ownershipMode() == OwnershipMode.MANUAL
        ((ContainerRunSupport) tracked.create(cloneDir, 'T-t', segments(), pipeline(), []))
        .environments.ownershipMode() == OwnershipMode.TRACKED
    }

    // FR18, FR22 of supervise-daemon-loops-and-embed-dashboard (design D22): the equipment the
    // supports are given is the one every run's box timing carries — no run builds its own.
    def "every run of either mode measures on the time equipment the supports were given"() {
        given:
        def supports = newSupports()

        when:
        def manualRun = (ContainerRunSupport) supports.manualSupport().create(cloneDir, 'T-m', segments(), pipeline(), [])
        def trackedRun = (ContainerRunSupport) supports.takeSupport().containerSupportFactory()
                .create(cloneDir, 'T-t', segments(), pipeline(), [])

        then:
        manualRun.environments.timing().equipment().is(time)
        trackedRun.environments.timing().equipment().is(time)
    }

    // FR18 of supervise-daemon-loops-and-embed-dashboard (design D22): manual runs and take/serve
    // ask one execution-mode selector — the one the supports were given.
    def "a manual run's plan and take's bundle ask the selector the supports were given"() {
        given:
        def supports = newSupports()

        expect: 'the bundle carries the very selector'
        supports.takeSupport().modeSelector().is(selector)

        and: 'the manual plan is the answer of that selector: container by default, over the scripted probe'
        supports.plan(pipeline(), RegisteredCloneFixture.unregistered(tempDir.resolve('home'), cloneDir)).mode() ==
                SandboxModeSelector.Plan.Mode.CONTAINER
    }

    private ContainerSupports newSupports() {
        new ContainerSupports([:], testProperties(), sandbox, selector, TaskGitFixture.real(), time)
    }

    private static List<Segment> segments() {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }
}
