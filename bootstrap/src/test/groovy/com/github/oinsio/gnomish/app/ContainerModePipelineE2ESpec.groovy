package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
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
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
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
 * <p>Implements M2, FR3, FR5, FR21, FR25 of add-sandbox-core; M1 of fix-operator-blockers
 * (container mode).
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
    String taskId = 'CTN-PIPE-1'

    // Wired per feature, so it gets its own repository — see GiteaContainerFixture's sharing rule.
    String originUrl

    def setupSpec() {
        gitea.start()
    }

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'container-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        Files.writeString(cloneDir.resolve('.gnomish/acceptance.md'), '- output.txt exists\n')
        commitAll(cloneDir)
        originUrl = gitea.createRepository("container-pipeline-${System.nanoTime()}")
        addRemote(cloneDir, 'origin', originUrl)
        gitExitCode(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
    }

    def cleanup() {
        ContainerE2eDocker.removeTaskObjects(taskId)
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'work', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md',
                (List<VerifyCheck>) [
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

    private static final String JUDGE_MODEL = 'claude-fake-judge-1'

    /** One executor round and one judge vote, and nothing else that could fail the stage. */
    private static StageDefinition judgedStage() {
        new StageDefinition(
                'work', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md',
                (List<VerifyCheck>) [
                    new VerifyCheck.Judge('acceptance.md', JUDGE_MODEL, [:], 1)
                ],
                new AutonomyLimits(1), AdvancementMode.AUTO)
    }

    private static boolean carriesPair(List<String> argv, String flag, String value) {
        (0..<argv.size() - 1).any { argv[it] == flag && argv[it + 1] == value }
    }

    // M2: clone into the box, round in the box, harvest, verification against the attempt
    // commit, factory-side push — end to end against a real HTTP-auth remote.
    def "a container-mode run completes: rounds in the box, harvest, outside push, disposed environment"() {
        given: 'a container-mode runner over the fake-agent sandbox image'
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def sandbox = new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def git = TaskGitFixture.real()
        def runner = new ContainerGitModeRunner(
                newAssembly(factoryProps), git, sandbox, factoryProps, ContainerSupportFixture.real(git.epochs()),
                LiveConsoleIO.onStdout())
        def segments = [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]

        when:
        runner.run(new RunOrder(cloneDir, null, pipeline(), false),
                segments, new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'),
                List.<Decision> of()), TaskState.atStageStart('work'))

        then: 'the snapshot-first protocol is on the branch: snapshot commit, then the state commit on top'
        def branch = "gnomish/${taskId}"
        def snapshotSha = gitOutput(cloneDir, 'log', branch, '--format=%H', '--grep',
                '^gnomish: snapshot work#0 [0-9a-f][0-9a-f]*$')
        snapshotSha
        def stateSha = gitOutput(cloneDir, 'log', branch, '--format=%H', '--grep',
                '^gnomish: round work#0$')
        stateSha
        gitOutput(cloneDir, 'rev-parse', "${stateSha}^") == snapshotSha

        and: 'the gnome round really ran inside the box: the fake agent wrote output.txt into the snapshot'
        gitOutput(cloneDir, 'ls-tree', '-r', '--name-only', snapshotSha).contains('output.txt')

        and: 'the completed tip is cleaned: no .gnomish-task/, the work is present'
        def tipTree = gitOutput(cloneDir, 'ls-tree', '-r', '--name-only', branch)
        tipTree.contains('output.txt')
        !tipTree.contains('.gnomish-task/')

        and: 'the branch reached the real remote via the factory-side push — proven from a fresh clone'
        def freshClone = tempDir.resolve('fresh-verify-clone')
        seedClone(tempDir, originUrl, freshClone)
        fetchFromOrigin(freshClone, "refs/heads/${branch}:refs/remotes/origin/${branch}")
        gitExitCode(freshClone, 'cat-file', '-e', stateSha) == 0

        and: 'the task environment is disposed: no container, volume, or network object remains'
        ContainerE2eDocker.taskObjects(taskId).isEmpty()
    }

    // M1, FR1–FR3 of fix-operator-blockers (container mode): the executor round's argv is read
    // back from the snapshot commit it was harvested into; the judge vote, whose fresh box is gone
    // by the time the run returns, checks its own argv in the box and passes only when it carries
    // dontAsk and the MCP exclusion (FakeAgentSandboxImage.ensureBuiltCheckingArgv).
    def "M1: in container mode the executor round and the judge vote launch with their permission mode and the MCP exclusion"() {
        given: 'a container-mode runner over the argv-checking fake agent, one attempt allowed'
        def image = FakeAgentSandboxImage.ensureBuiltCheckingArgv(JUDGE_MODEL)
        def sandbox = new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.ARGV_CHECKING_BINARY)
        def git = TaskGitFixture.real()
        def runner = new ContainerGitModeRunner(
                newAssembly(factoryProps), git, sandbox, factoryProps, ContainerSupportFixture.real(git.epochs()),
                LiveConsoleIO.onStdout())
        def segments = [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [judgedStage()])
        ]

        when:
        runner.run(new RunOrder(cloneDir, null, new PipelineDefinition('1', new AutonomyLimits(1), [judgedStage()]),
        false),
        segments, new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'),
        List.<Decision> of()), TaskState.atStageStart('work'))

        then: 'the executor round captured exactly one argv, harvested with the snapshot — never an empty file'
        def branch = "gnomish/${taskId}"
        def snapshotSha = gitOutput(cloneDir, 'log', branch, '--format=%H', '--grep',
                '^gnomish: snapshot work#0 [0-9a-f][0-9a-f]*$')
        def captured = gitOutput(cloneDir, 'show', "${snapshotSha}:${FakeAgentSandboxImage.EXECUTOR_ARGV_CAPTURE}")
                .readLines()
        captured.count('---') == 1
        def executorArgv = captured - ['---']
        carriesPair(executorArgv, '--permission-mode', 'acceptEdits')
        executorArgv.contains('--strict-mcp-config')
        !executorArgv.contains('bypassPermissions')

        and: 'the judge vote passed, which the in-box check allows only for dontAsk with the MCP exclusion'
        def tipTree = gitOutput(cloneDir, 'ls-tree', '-r', '--name-only', branch)
        !tipTree.contains('.gnomish-task/')
    }
}
