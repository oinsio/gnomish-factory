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
 * Task 7.4 of add-serve-sandbox-lifecycle (M3, FR7, FR8): coexistence on one shared Docker
 * namespace has zero cross-touches. Two properties, both structural (design D1, D5, D7):
 *
 * <ul>
 *   <li>Two projects sharing one Docker host — a sweep is scoped to its own project identity
 *       (derived from the clone's own {@code origin} remote URL) at LISTING time, so a second
 *       project's zombie is invisible to this project's sweep, never merely spared by a check.
 *   <li>{@code run} beside a sweep — a {@code manual}-mode object (what {@code gnomish run}
 *       creates) is governed by age alone, never by the tracker-driven oracle; a manual object
 *       well under its own running-stop threshold survives a sweep tick exactly as a second
 *       project's object does, for the same "different population, not merely spared" reason.
 * </ul>
 *
 * <p>"Second instance" zero-cross-touch is a different mechanism and lives in its own specs: two
 * instances on the SAME project share one project identity, so scoping cannot separate them and
 * the claim oracle is the guard — {@code SandboxLifecycleCrossInstanceE2ESpec} proves exactly that,
 * with {@code SandboxLifecycleLaunchRaceE2ESpec} (task 7.3) covering the minimum-age angle and
 * {@code TakeSlotRunnerContainerConcurrencySpec} (task 5.3) the concurrent-slot angle. This spec's
 * own job is the project- and mode-scoping angle none of those cover.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 *
 * <p>Implements M3, FR7, FR8 of add-serve-sandbox-lifecycle.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class SandboxLifecycleProjectScopingE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    def gitRunner = new GitProcessRunner()
    List<String> taskIds = []

    def setup() {
    }

    def cleanup() {
        taskIds.each { ContainerE2eDocker.removeTaskObjects(it) }
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

    private Path cloneWithOrigin(String name, String originUrl) {
        def dir = initWorkingRepo(tempDir, name)
        Files.writeString(dir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(dir, 'add', 'instructions.md')
        gitRunner.run(dir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        gitRunner.run(dir, 'remote', 'add', 'origin', originUrl)
        dir
    }

    private String materializeRunningBox(Path cloneDir, String taskId, SandboxProperties sandboxProps, OwnershipMode mode) {
        taskIds << taskId
        def support = ContainerRunSupport.create(cloneDir, taskId, segments(), sandboxProps,
                List.<String> of(), [], mode)
        support.taskRepository().createTask(new TaskContext(taskId, 'title', 'body', List.<Decision> of()), 'HEAD')
        support.lease().environmentFor('work')
        def boxName = "gnomish-box-${taskId}"
        assert ContainerE2eDocker.containerRunning(boxName)
        Thread.sleep(1500) // clear the tiny minimum-age threshold below with real margin
        boxName
    }

    // FR8, M3: two projects, one Docker host — project A's sweep never sees project B's zombie,
    // because project scoping filters at LISTING time, not by a post-hoc ownership check. Both
    // boxes are equally "unowned & running"; only A's is even visible to A's sweep.
    def "a sweep scoped to project A never touches project B's unowned running box"() {
        given: 'two projects on one host, each with its own origin remote (and so its own project identity)'
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def tinyMinAge = new SandboxProperties(image, null, null, null, [], [], false, null,
        Duration.ofMillis(1), Duration.ofDays(7), Duration.ofHours(24))
        def projectA = cloneWithOrigin('project-a', 'https://example.invalid/org/project-a.git')
        def projectB = cloneWithOrigin('project-b', 'https://example.invalid/org/project-b.git')
        def taskA = "CTN-SCOPEA-${System.nanoTime() % 100000}"
        def taskB = "CTN-SCOPEB-${System.nanoTime() % 100000}"
        def boxA = materializeRunningBox(projectA, taskA, tinyMinAge, OwnershipMode.TRACKED)
        def boxB = materializeRunningBox(projectB, taskB, tinyMinAge, OwnershipMode.TRACKED)

        when: 'project A alone runs a sweep tick, its oracle omitting every task (worst case for both)'
        def pass = SandboxLifecyclePassFactory.create(tinyMinAge, Clock.systemUTC())
        pass.run(projectA, new LivenessVerdict.Live(Set.of()))

        then: 'A stopped its own unowned running box'
        !ContainerE2eDocker.containerRunning(boxA)

        and: 'B is untouched — invisible to a sweep scoped to a different project identity'
        ContainerE2eDocker.containerRunning(boxB)
    }

    // FR7: a manual-mode object (gnomish run's own labelling) is governed by age alone, so a
    // tracked-mode sweep evaluation — even one whose oracle omits every task — never stops it: the
    // running-stop threshold, not the oracle, is what protects it.
    def "a manual-mode box survives a sweep tick under its own running-stop threshold, oracle notwithstanding"() {
        given: 'a manual-mode box (as gnomish run creates), well under the default 24h running-stop age'
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def tinyMinAge = new SandboxProperties(image, null, null, null, [], [], false, null,
        Duration.ofMillis(1), Duration.ofDays(7), Duration.ofHours(24))
        def project = cloneWithOrigin('project-manual', 'https://example.invalid/org/project-manual.git')
        def taskId = "CTN-MANUAL-${System.nanoTime() % 100000}"
        def box = materializeRunningBox(project, taskId, tinyMinAge, OwnershipMode.MANUAL)

        when: 'a sweep tick evaluates the host with an empty liveness verdict'
        def pass = SandboxLifecyclePassFactory.create(tinyMinAge, Clock.systemUTC())
        pass.run(project, new LivenessVerdict.Live(Set.of()))

        then: 'the manual box is untouched — its own 24h age threshold governs it, not the oracle'
        ContainerE2eDocker.containerRunning(box)
    }
}
