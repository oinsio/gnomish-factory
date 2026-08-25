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
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * Task 7.3 of add-serve-sandbox-lifecycle (M2): a slot launch concurrent with a sweep tick never
 * loses the launching environment. Design D3: birth and ownership are one atomic operation
 * ({@code docker create --label ...}), so the residual window — an object listable before its
 * task's claim is observable to the oracle — is covered by the minimum-age guard alone, never by
 * any create-after-sweep ordering.
 *
 * <p>This spec proves exactly that residual window over real Docker: a box is materialized (the
 * "launching slot"), then a sweep tick evaluates the host with a liveness verdict that does NOT
 * yet include this task — the worst case, an oracle that has not observed the launching task's
 * claim yet — using the real, un-overridden minimum-age default. The box survives untouched.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 *
 * <p>Implements M2 of add-serve-sandbox-lifecycle.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class SandboxLifecycleLaunchRaceE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    Path cloneDir
    String taskId

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'race-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        commitAll(cloneDir)
    }

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

    // M2: a slot launch (box materialized, labelled from birth) races a sweep tick whose liveness
    // verdict has not yet observed this task's claim — the real minimum-age default (2m) protects
    // it regardless, so the launching environment is never lost to a concurrent tick.
    def "a just-launched box under the default minimum age survives a sweep tick that has not observed its claim yet"() {
        given: 'a box is materialized for a task the oracle has not observed yet — the launch race'
        taskId = "CTN-RACE-${System.nanoTime() % 100000}"
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        // The real, un-overridden minimum-age default (SandboxProperties' own 2 minutes) — the
        // exact protection this spec is proving, not something to bypass.
        def sandboxProps = new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
        def support = ContainerRunSupport.create(cloneDir, taskId, segments(), sandboxProps,
                new FactoryProperties(null, null, null, null, null), List.<String> of(), [], OwnershipMode.TRACKED)
        support.taskRepository().createTask(new TaskContext(taskId, 'title', 'body', List.<Decision> of()), 'HEAD')
        support.lease().environmentFor('work')
        def boxName = "gnomish-box-${taskId}"
        assert ContainerE2eDocker.containerRunning(boxName)

        when: 'a sweep tick evaluates the host with a liveness verdict that omits this task entirely'
        def pass = SandboxLifecyclePassFactory.create(sandboxProps, new FactoryProperties(null, null, null, null, null), Clock.systemUTC())
        pass.run(cloneDir, new LivenessVerdict.Live(Set.of()))

        then: 'the launching box is untouched — still running, nothing stopped or disposed'
        ContainerE2eDocker.containerRunning(boxName)
        ContainerE2eDocker.taskObjects(taskId).size() == 4 // box + guard + volume + network, all still present
    }
}
