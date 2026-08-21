package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
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
 * FR5/NFR-C1 of add-serve-sandbox-lifecycle against a real daemon: an aged network remnant — one
 * whose box, guard and volume are all gone — is reaped by age. The container half of the sweep is
 * covered by {@code SandboxLifecycleZombieE2ESpec}; this spec's own job is the remnant half, whose
 * age comes from a different {@code docker inspect} field per object kind ({@code {{.CreatedAt}}}
 * on a volume, {@code {{json .Created}}} on a network) — a difference only a real daemon can
 * settle, since the network's field is a Go {@code time.Time} whose default rendering is not
 * RFC3339 at all. A volume remnant is left to the unit specs: its disposal cascades to the
 * network, so the two together cannot isolate either object's own timestamp reading.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 *
 * <p>Implements FR5, NFR-C1 of add-serve-sandbox-lifecycle.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class SandboxLifecycleRemnantReapE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    def gitRunner = new GitProcessRunner()
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
        gitRunner.run(dir, 'add', 'instructions.md')
        gitRunner.run(dir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        gitRunner.run(dir, 'remote', 'add', 'origin', "https://example.invalid/org/${name}.git")
        dir
    }

    // FR5, NFR-C1: a network left alone — its box, guard and volume already gone — is reaped by
    // age. Its created-at comes from `docker network inspect` on a Go time.Time field, whose
    // default rendering is not RFC3339; when that value failed to parse, the network reached NO
    // verdict at all and was neither reaped nor reported, leaking one network per task forever.
    def "an aged network-only remnant is reaped, not silently skipped"() {
        given: 'a materialized box, then container, guard and volume all removed — the network alone survives'
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def tinyAges = new SandboxProperties(image, null, null, null, [], [], false, null,
        Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofHours(24))
        def project = cloneWithOrigin('remnant-project')
        taskId = "CTN-REMNANT-${System.nanoTime() % 100000}"
        def support = ContainerRunSupport.create(project, taskId, segments(), tinyAges,
                List.<String> of(), [], OwnershipMode.TRACKED)
        support.taskRepository().createTask(new TaskContext(taskId, 'title', 'body', List.<Decision> of()), 'HEAD')
        support.lease().environmentFor('work')
        assert ContainerE2eDocker.containerRunning("gnomish-box-${taskId}")
        ContainerE2eDocker.removeContainer("gnomish-box-${taskId}")
        ContainerE2eDocker.removeContainer("gnomish-guard-${taskId}")
        ContainerE2eDocker.removeVolume("gnomish-vol-${taskId}")
        assert ContainerE2eDocker.taskObjects(taskId) == [
            "gnomish-net-${taskId}".toString()
        ]
        // Docker timestamps have sub-second precision but the host clock may lag; a real sleep
        // keeps the remnant reliably past the 1ms thresholds above regardless of rounding.
        Thread.sleep(1500)

        when: 'a sweep tick evaluates the host with a liveness verdict that omits this task'
        def summary = SandboxLifecyclePassFactory.create(tinyAges, Clock.systemUTC())
                .run(project, new LivenessVerdict.Live(Set.of()))

        then: 'the network is gone, and the pass reported the verdict that removed it'
        summary == 'sweep: 1 disposed-aged'
        ContainerE2eDocker.taskObjects(taskId).isEmpty()
    }
}
