package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * Task 7.4 of add-serve-sandbox-lifecycle (M3, FR8), the second-instance leg: two serve instances
 * working the SAME project share one project identity, so — unlike two different projects, which
 * {@code SandboxLifecycleProjectScopingE2ESpec} proves never see each other at listing time — each
 * instance's sweep DOES list the other's boxes. What keeps them apart is the claim oracle alone:
 * instance A's tick receives a {@link LivenessVerdict.Live} naming instance B's claimed task, and
 * must leave B's still-running box alone.
 *
 * <p>The minimum-age guard is deliberately taken out of the picture (thresholds set to 1&nbsp;ms
 * and a real sleep past them), so the box's survival can only be the oracle's doing — the same
 * box, with the same age, is stopped by the very next tick once the verdict stops naming it.
 * {@code SandboxLifecycleLaunchRaceE2ESpec} covers the opposite arrangement: protection by
 * minimum age while the oracle has not observed the claim yet.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 *
 * <p>Implements M3, FR8 of add-serve-sandbox-lifecycle.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class SandboxLifecycleCrossInstanceE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    String taskId

    def cleanup() {
        if (taskId != null) {
            ContainerE2eDocker.removeTaskObjects(taskId)
        }
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'work', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static List<Segment> segments() {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]
    }

    private Path cloneWithOrigin(String name) {
        def dir = initWorkingRepo(tempDir, name)
        Files.writeString(dir.resolve('instructions.md'), 'build it\n')
        commitAll(dir)
        addRemote(dir, 'origin', "https://example.invalid/org/${name}.git")
        dir
    }

    // FR8, M3: the cross-instance guard is the oracle, not the listing. Instance A's sweep lists
    // instance B's box (same project identity, same Docker host) and spares it solely because the
    // verdict names B's claim as fresh.
    def "a sibling instance's claimed box survives this instance's sweep, and is stopped once the claim is gone"() {
        given: 'instance B has a running box for a task it claims, on the shared project identity'
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        // Thresholds at 1ms remove the minimum-age protection entirely, so nothing but the oracle
        // can account for the box surviving the first tick.
        def tinyAges = new SandboxProperties(image, null, null, null, [], [], false, null,
        Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofHours(24))
        def project = cloneWithOrigin('cross-instance-project')
        taskId = "CTN-SIBLING-${System.nanoTime() % 100000}"
        def support = ContainerRunSupport.create(project, taskId, segments(), tinyAges,
                new FactoryProperties(null, null, null, null, null), List.<String> of(), [], OwnershipMode.TRACKED)
        support.taskRepository().createTask(new TaskContext(taskId, 'title', 'body', List.<Decision> of()), 'HEAD')
        support.lease().environmentFor('work')
        def boxName = "gnomish-box-${taskId}".toString()
        assert ContainerE2eDocker.containerRunning(boxName)
        // Docker timestamps have sub-second precision but the host clock may lag; a real sleep puts
        // the box reliably past the 1ms thresholds, so the age guard cannot be what spares it.
        Thread.sleep(1500)
        def pass = SandboxLifecyclePassFactory.create(tinyAges, new FactoryProperties(null, null, null, null, null), Clock.systemUTC())

        when: "instance A's tick runs with a verdict naming the sibling's claim as fresh"
        def spared = pass.run(project, new LivenessVerdict.Live(Set.of(taskId)))

        then: "B's box is untouched — running, with its whole object set intact"
        ContainerE2eDocker.containerRunning(boxName)
        ContainerE2eDocker.taskObjects(taskId).size() == 4 // box + guard + volume + network
        spared.contains('checked-alive')

        when: 'the sibling dies and the next tick sees a verdict that no longer names its claim'
        def swept = pass.run(project, new LivenessVerdict.Live(Set.of()))

        then: 'the very same box, at the very same age, is now stopped — the oracle was the guard'
        !ContainerE2eDocker.containerRunning(boxName)
        swept.contains('stopped-orphan')
    }
}
