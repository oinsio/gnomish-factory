package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.git.ProjectScope
import com.github.oinsio.gnomish.app.lease.BlockingSleeper
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
import com.github.oinsio.gnomish.app.serve.BranchPass
import com.github.oinsio.gnomish.app.serve.OccupiedSlots
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR14 of add-factory-serve; FR5, FR6 of add-serve-sandbox-lifecycle: an escalated task's kept
 * container environment ages past the threshold. The two age-based cleaners each own one
 * namespace: the worktree cleaner ({@link WorktreeJanitor}, its startup tick on its own loop) looks
 * only at host worktrees and leaves the container alone; the aged reaper of the sandbox sweep
 * ({@link SandboxLifecycleSweep} over {@link FakeDockerHost}, whose objects really go away)
 * disposes the box, its volume and its network by the age the runtime reports for them. Nothing of
 * the environment is left, so the resume that follows — the real {@link
 * ContainerTaskExecutionEnvironment} materializing against the same host — finds no box to
 * reattach to and builds a fresh one, its working copy seeded from the task branch.
 */
@Timeout(10)
class AgedContainerEnvironmentReapSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-08-07T12:00:00Z')
    static final Instant OLD = NOW - Duration.ofDays(30)
    static final String KEY = 'k1'
    static final String BOX = 'gnomish-box-k1'
    static final String BRANCH = 'gnomish/PROJ-1'
    static final String IMAGE = 'gnomish/img'
    static final Path CLONE = Path.of('/factory/project-clone')
    static final ObjectOwnership OWNERSHIP = new ObjectOwnership(OwnershipMode.TRACKED, FakeDockerHost.PROJECT)

    @TempDir
    Path tempDir

    def host = new FakeDockerHost()
    def verdicts = []

    private static Map<String, String> labels() {
        [
            (FactoryDockerLabels.FACTORY_LABEL): 'true',
            (FactoryDockerLabels.TASK_LABEL): KEY,
            (FactoryDockerLabels.MODE_LABEL): 'tracked',
            (FactoryDockerLabels.PROJECT_LABEL): FakeDockerHost.PROJECT
        ]
    }

    /** One startup tick of the worktree cleaner, run on its own loop, then stopped. */
    private List<String> worktreeCleanerDisposals() {
        def disposed = []
        def sleeper = new BlockingSleeper()
        def clone = RegisteredCloneFixture.unregistered(tempDir.resolve('home'), tempDir.resolve('project'))
        Files.createDirectories(clone.worktrees())
        def janitor = new WorktreeJanitor(clone, Duration.ofDays(14), { String key ->
            disposed << key
        } as TaskEnvironmentDisposal,
        VirtualTimeEquipment.on(new VirtualClock(NOW), sleeper), {
            -> Set.of()
        } as OccupiedSlots, { Set<String> held -> } as BranchPass)
        janitor.start()
        sleeper.awaitEntered()
        Thread.ofVirtual().start {
            janitor.stopAndJoin()
        }.join(Duration.ofSeconds(2))
        disposed
    }

    // Scenario: serve/factory-serve / Aged container environment removed by runtime age
    def "FR14: the worktree cleaner leaves an aged kept container alone, and the aged reaper disposes its triple by runtime age"() {
        given: 'an escalated task\'s environment: its box stopped a month ago, with its volume and network'
        host.add('gnomish-box-k1', ObjectKind.CONTAINER, labels(), OLD, false, OLD)
        host.add('gnomish-vol-k1', ObjectKind.VOLUME, labels(), OLD)
        host.add('gnomish-net-k1', ObjectKind.NETWORK, labels(), OLD)

        when: 'the worktree cleaner runs its startup tick'
        def disposedByCleaner = worktreeCleanerDisposals()

        then: 'it touches nothing: the box is not a host worktree'
        disposedByCleaner.isEmpty()
        host.objects.size() == 3

        when: 'the sandbox sweep judges the host, with no live claim on the task'
        new SandboxLifecycleSweep(new RecordingDockerCli(onRun: host.commands()), host.disposal(),
        { SweepVerdict v -> verdicts << v } as SweepVerdictListener)
        .evaluate(new ProjectScope(FakeDockerHost.PROJECT, Optional.empty()), new LivenessVerdict.Live([] as Set), NOW,
        new SandboxLifecycleThresholds(Duration.ofMinutes(5), Duration.ofDays(7), Duration.ofHours(24)))

        then: 'the aged reaper disposed the whole key triple, by the age the runtime reported'
        verdicts*.category() == [
            SweepVerdictCategory.DISPOSED_AGED
        ]
        verdicts[0].taskKey() == KEY
        host.objects.isEmpty()

        when: 'the task is resumed: its environment is materialized on the same host'
        def docker = resumeOn(host)

        then: 'no box is left to reattach to: a fresh network, volume and box, the working copy seeded from the task branch'
        def created = docker.runs.findAll {
            it != DockerCommands.inspectContainerState(BOX) && it != DockerCommands.inspectImageConfig(IMAGE) &&
            it.takeRight(2) != ['git', '--version']
        }
        created.take(3) == [
            DockerCommands.createNetwork(KEY, OWNERSHIP),
            DockerCommands.createVolume(KEY, OWNERSHIP),
            DockerCommands.seedClone(KEY, IMAGE, CLONE.toString(), BRANCH, null, OWNERSHIP)
        ]
        !docker.runs.contains(DockerCommands.startContainer(BOX))
    }

    /**
     * The resume's materialize, through the real {@link ContainerTaskExecutionEnvironment}, against
     * the docker host the sweep just left: an inspect of a removed object fails as the daemon's
     * does, and every create is applied.
     */
    private static RecordingDockerCli resumeOn(FakeDockerHost host) {
        def docker = new RecordingDockerCli()
        docker.onRun = { List<String> args ->
            RecordingDockerCli.answerDeclaredVolumes(args) ?:
            (args[0] == 'inspect' && !host.objects.containsKey(args.last())
            ? DockerResult.of(1, '', 'Error: No such object') : host.commands().call(args))
        }
        new ContainerTaskExecutionEnvironment(
                BoxEquipmentFixture.forBox(docker, IMAGE, new ResourceLimits('2', '2g', 512L, '10g'), new VirtualClock(NOW), OWNERSHIP.mode()),
                KEY, new BoxGitLink(CLONE, { String c, String b -> } as ContainerHarvest), ChildEnvAllowlist.none(), OWNERSHIP.projectId()).materialize(BRANCH, null)
        docker
    }
}
