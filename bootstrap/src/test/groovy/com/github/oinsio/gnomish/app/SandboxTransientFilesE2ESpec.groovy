package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * Where a sandboxed run keeps its transient files, against a real daemon: the egress guard's
 * configuration, rendered by the composition root's {@link ContainerRunSupportFactory} under the
 * system temporary directory and never under the factory home (FR1 of add-project-registry); and a
 * command check's findings channel, which lives inside the task environment and never on the
 * factory's own filesystem (FR4 of add-sandbox-core). Both are brought up for a real task through
 * the production container support.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 *
 * <p>Implements FR1 of add-project-registry; FR4 of add-sandbox-core.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class SandboxTransientFilesE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    String taskId
    Path project
    ContainerRunSupport support

    def cleanup() {
        if (taskId != null) {
            ContainerE2eDocker.removeTaskObjects(taskId)
            guardConfig().toFile().deleteDir()
        }
    }

    // Scenario: operator/project-registry / Egress-guard configuration stays in the temporary directory
    def "FR1: a sandboxed task's guard renders its configuration under the system temporary directory"() {
        given:
        startTask('GUARDCFG')

        when: 'the stage environment comes up, its guard with it'
        support.lease().environmentFor('work')

        then: 'the guard ran, and its rendered configuration is under the system temporary directory'
        ContainerE2eDocker.containerRunning("gnomish-guard-${taskId}")
        Files.readString(guardConfig().resolve('allowlist.json')).contains('example.com')
        Files.isRegularFile(guardConfig().resolve('guard.py'))

        and: 'not beside the clone either: the run kept no guard state of its own anywhere else'
        !guardConfig().startsWith(project)
    }

    // Scenario: cli/manual-run / Sandboxed runner files stay in the environment
    def "FR4: a sandboxed command check's findings file lives in the task environment, not on the factory's filesystem"() {
        given: 'a running task environment and the run\'s own check-environment source'
        startTask('FINDINGS')
        def box = support.lease().environmentFor('work')
        def runner = new ShellCommandCheckRunner(new VirtualClock()).withEnvironments(support.pieces(null).checkEnvironments())

        when: 'a command check reports its own findings path as its finding'
        def verdict = runner.run(new VerifyCheck.Command(
                        'printf \'{"findings":[{"message":"%s"}]}\' "$GNOMISH_FINDINGS_FILE" > "$GNOMISH_FINDINGS_FILE"; exit 1'),
                support.workspace())

        then: 'the runner read the findings back from inside the box'
        verdict instanceof Verdict.Fail
        def findingsPath = (verdict as Verdict.Fail).findings()[0].message()
        findingsPath.startsWith(box.scratchRoot() + '/')

        and: 'no such file exists on the factory side: not at that path, not in the temp directory, not in the clone'
        !Files.exists(Path.of(findingsPath))
        def name = Path.of(findingsPath).fileName.toString()
        !Files.exists(Path.of(System.getProperty('java.io.tmpdir'), name))
        Files.walk(project).withCloseable { paths ->
            paths.noneMatch {
                it.fileName?.toString() == name
            }
        }
    }

    /** A clone with its task branch and the container support of one task, keyed {@code CTN-<label>-<n>}. */
    private void startTask(String label) {
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def sandbox = new SandboxProperties(image, null, null, null, ['example.com'], [], false, null, null, null, null)
        project = initWorkingRepo(tempDir, 'transient-files-project')
        Files.createDirectories(project.resolve('.gnomish'))
        Files.writeString(project.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(project)
        taskId = "CTN-${label}-${System.nanoTime() % 100000}"
        support = ContainerSupportFixture.direct(project, taskId, segments(), sandbox,
                new FactoryProperties(null, null, null, null), OwnershipMode.MANUAL, ClaimEpochSource.NONE)
        support.taskRepository().createTask(
                new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                TaskStart.commit(project, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('work'))
        // The branch's first push needs no origin; project identity is derived from the URL added after it.
        addRemote(project, 'origin', 'https://example.invalid/org/transient-files-project.git')
    }

    private Path guardConfig() {
        Path.of(System.getProperty('java.io.tmpdir'), 'gnomish-guard', taskId)
    }

    private static List<Segment> segments() {
        def stage = new StageDefinition(
                'work', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage])
        ]
    }
}
