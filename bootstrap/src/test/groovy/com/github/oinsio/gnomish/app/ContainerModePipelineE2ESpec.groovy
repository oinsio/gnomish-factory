package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.e2e.gitea.GiteaContainerFixture
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * M2 of add-sandbox-core (task 9.2): the full pipeline completes in container
 * mode against a real Gitea remote — the working copy is cloned into a task
 * volume, the fake-agent round runs inside the box (with the guard up and the
 * self-check passed), the snapshot and state commits are harvested into the
 * factory clone, verification reads the attempt commit as bare objects, and
 * the push to the real remote happens factory-side, outside the environment.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no
 * pullable mitmproxy image.
 *
 * <p>Implements M2, FR3, FR5, FR21, FR25 of add-sandbox-core.
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class ContainerModePipelineE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    Path cloneDir
    def gitRunner = new GitProcessRunner()
    String taskId = 'CTN-PIPE-1'

    // Wired per feature, so it gets its own repository — see GiteaContainerFixture's sharing rule.
    String originUrl

    def setupSpec() {
        gitea.start()
    }

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'container-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        originUrl = gitea.createRepository("container-pipeline-${System.nanoTime()}")
        gitRunner.run(cloneDir, 'remote', 'add', 'origin', originUrl)
        gitRunner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
    }

    def cleanup() {
        ContainerE2eDocker.removeTaskObjects(taskId)
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'work', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md',
                [
                    new VerifyCheck.Builtin('files_exist', [files: ['output.txt']]),
                    // FR13/UX5: the final gate runs in a fresh box materialized from the attempt
                    // commit — passing proves the branch alone is self-sufficient.
                    new VerifyCheck.Command('test -f output.txt', VerifyCheck.VerifyIn.FRESH_BOX),
                ],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    // M2: clone into the box, round in the box, harvest, verification against the attempt
    // commit, factory-side push — end to end against a real HTTP-auth remote.
    def "a container-mode run completes: rounds in the box, harvest, outside push, disposed environment"() {
        given: 'a container-mode runner over the fake-agent sandbox image'
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def sandbox = new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def runner = new ContainerGitModeRunner(
                newAssembly(factoryProps), TaskGitFixture.real(), sandbox, factoryProps, ContainerSupportFixture.real())
        def segments = [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]

        when:
        runner.run(cloneDir, null, pipeline(), segments, new TaskContext(taskId, 'title', 'body',
                List.<Decision> of()), TaskState.atStageStart('work'), RunArguments.InteractiveMode.NONE)

        then: 'the snapshot-first protocol is on the branch: snapshot commit, then the state commit on top'
        def branch = "gnomish/${taskId}"
        def snapshotSha = gitRunner.run(cloneDir, 'log', branch, '--format=%H', '--grep',
                '^gnomish: snapshot work#0$').stdout().trim()
        snapshotSha
        def stateSha = gitRunner.run(cloneDir, 'log', branch, '--format=%H', '--grep',
                '^gnomish: round work#0$').stdout().trim()
        stateSha
        gitRunner.run(cloneDir, 'rev-parse', "${stateSha}^").stdout().trim() == snapshotSha

        and: 'the gnome round really ran inside the box: the fake agent wrote output.txt into the snapshot'
        gitRunner.run(cloneDir, 'ls-tree', '-r', '--name-only', snapshotSha).stdout().contains('output.txt')

        and: 'the completed tip is cleaned: no .gnomish-task/, the work is present'
        def tipTree = gitRunner.run(cloneDir, 'ls-tree', '-r', '--name-only', branch).stdout()
        tipTree.contains('output.txt')
        !tipTree.contains('.gnomish-task/')

        and: 'the branch reached the real remote via the factory-side push — proven from a fresh clone'
        def freshClone = tempDir.resolve('fresh-verify-clone')
        gitRunner.run(tempDir, 'clone', originUrl, freshClone.toString())
        gitRunner.run(freshClone, 'fetch', 'origin', "${branch}:refs/remotes/origin/${branch}")
        gitRunner.run(freshClone, 'cat-file', '-e', stateSha).exitCode() == 0

        and: 'the task environment is disposed: no container, volume, or network object remains'
        ContainerE2eDocker.taskObjects(taskId).isEmpty()
    }
}
