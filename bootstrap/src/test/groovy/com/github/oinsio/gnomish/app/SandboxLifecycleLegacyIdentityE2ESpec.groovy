package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.git.ProjectIdentity
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
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
 * Tasks 4.1 and 4.2 of normalize-project-identity-url (M2, G2, UX1, FR1, FR3), against a real
 * Docker daemon and a real git clone — the two properties the whole change exists for:
 *
 * <ul>
 *   <li><b>No orphan.</b> A box stamped with the pre-normalization identity is found, classified
 *       and acted on by a sweep running under the normalized one. This is the guarantee the
 *       reasoning in design D4 asserts and that only a real listing can prove: the legacy label is
 *       a string on a live Docker object, and the sweep's scope either reaches it or it does not.
 *   <li><b>Rotation is invisible.</b> Rewriting the credential embedded in the clone's {@code
 *       origin} URL between two passes changes neither the resolved identity nor the pass's
 *       verdict for the same box — the failure that motivated the change, run forwards.
 * </ul>
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 *
 * <p>Implements FR1, FR3, M2, G2, UX1 of normalize-project-identity-url.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class SandboxLifecycleLegacyIdentityE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    def gitRunner = new GitProcessRunner()
    List<String> taskIds = []

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

    /** A sandbox config whose minimum-age guard is effectively off; {@code projectId} may override. */
    private static SandboxProperties props(String image, String projectId = null) {
        new SandboxProperties(image, null, null, null, [], [], false, projectId,
        Duration.ofMillis(1), Duration.ofDays(7), Duration.ofHours(24))
    }

    private Path cloneWithOrigin(String name, String originUrl) {
        def dir = initWorkingRepo(tempDir, name)
        Files.writeString(dir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(dir, 'add', 'instructions.md')
        gitRunner.run(dir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        gitRunner.run(dir, 'remote', 'add', 'origin', originUrl)
        dir
    }

    private String materializeRunningBox(Path cloneDir, String taskId, SandboxProperties sandboxProps) {
        taskIds << taskId
        def support = ContainerRunSupport.create(cloneDir, taskId, segments(), sandboxProps,
                List.<String> of(), [], OwnershipMode.TRACKED)
        support.taskRepository().createTask(new TaskContext(taskId, 'title', 'body', List.<Decision> of()), 'HEAD')
        support.lease().environmentFor('work')
        def boxName = "gnomish-box-${taskId}"
        assert ContainerE2eDocker.containerRunning(boxName)
        Thread.sleep(1500) // clear the tiny minimum-age threshold above with real margin
        boxName
    }

    private static List<SweepVerdict> sweep(Path cloneDir, SandboxProperties sandboxProps, LivenessVerdict liveness) {
        def collected = []
        SweepVerdictListener sink = { SweepVerdict v -> collected << v }
        SandboxLifecyclePassFactory.create(sandboxProps, Clock.systemUTC()).run(cloneDir, liveness, sink)
        collected
    }

    // M2, G2, FR3: the no-orphan guarantee. The box is created under the digest of the RAW origin
    // URL — exactly what a pre-normalization factory stamped — and then swept by a factory that
    // derives the normalized identity. Without the legacy alias the sweep's listing would not
    // return it at all, and it would run forever with no pass able to reclaim it.
    def "a box stamped with the legacy identity is found and acted on by a sweep under the normalized one"() {
        given: 'a clone whose origin carries an embedded token, so raw and normalized digests differ'
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def rawUrl = "https://ghp_PRER0TAT10N@example.invalid/org/legacy-${System.nanoTime()}.git".toString()
        def clone = cloneWithOrigin('project-legacy', rawUrl)
        def scope = ProjectIdentity.resolveScope(null, Optional.of(rawUrl), clone)

        and: 'the two identities really do differ, or the spec would prove nothing'
        assert scope.legacyIdentity().isPresent()

        and: 'a running box stamped exactly as the pre-normalization factory would have stamped it'
        def taskId = "CTN-LEGACY-${System.nanoTime() % 100000}"
        def box = materializeRunningBox(clone, taskId, props(image, scope.legacyIdentity().get()))

        when: 'a sweep runs under the normalized identity, its oracle owning nothing'
        def verdicts = sweep(clone, props(image), new LivenessVerdict.Live(Set.of()))

        then: 'the legacy-labelled box is in scope — it is judged an orphan and stopped'
        verdicts*.objectName().contains(box)
        verdicts.find {
            it.objectName() == box
        }.category() == SweepVerdictCategory.STOPPED_ORPHAN

        and:
        !ContainerE2eDocker.containerRunning(box)
    }

    // UX1, FR1: rotating the credential in the origin URL has no visible effect on sweep behavior.
    // Both passes keep the box alive under the same oracle; what is being asserted is that the
    // second pass still SEES it — under a pre-normalization factory it would have vanished from
    // the listing the moment the token changed.
    def "rotating the origin credential between two passes changes neither the identity nor the verdicts"() {
        given:
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def path = "org/rotate-${System.nanoTime()}".toString()
        def before = "https://ghp_0LDT0KEN@example.invalid/${path}.git".toString()
        def after = "https://ghp_NEWT0KEN@example.invalid/${path}.git".toString()
        def clone = cloneWithOrigin('project-rotate', before)
        def taskId = "CTN-ROTATE-${System.nanoTime() % 100000}"
        def box = materializeRunningBox(clone, taskId, props(image))
        def liveness = new LivenessVerdict.Live(Set.of(TaskIdSanitizer.sanitize(taskId)))

        when: 'a pass runs, the credential is rotated in place, and a second pass runs'
        def first = sweep(clone, props(image), liveness)
        gitRunner.run(clone, 'remote', 'set-url', 'origin', after)
        def second = sweep(clone, props(image), liveness)

        then: 'the identity the two URLs derive is the same one'
        ProjectIdentity.resolve(null, Optional.of(before), clone) ==
                ProjectIdentity.resolve(null, Optional.of(after), clone)

        and: 'and the rotation left no legacy alias to lean on — the identity itself never moved'
        ProjectIdentity.resolveScope(null, Optional.of(after), clone).identity() ==
                ProjectIdentity.resolveScope(null, Optional.of(before), clone).identity()

        and: 'the box is in both passes, with the same verdict, and is still running'
        first.find {
            it.objectName() == box
        }?.category() == SweepVerdictCategory.CHECKED_ALIVE
        second.find {
            it.objectName() == box
        }?.category() == SweepVerdictCategory.CHECKED_ALIVE
        ContainerE2eDocker.containerRunning(box)
    }
}
