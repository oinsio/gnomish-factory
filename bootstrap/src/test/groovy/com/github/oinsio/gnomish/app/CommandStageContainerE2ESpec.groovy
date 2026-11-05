package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
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
 * FR9, NFR-R1, M1, M2, G2 of add-command-executor (task 7.1), container mode: the container {@code run}
 * path, assembled through the composition root's own wiring, runs the same {@link CommandStagePipeline}
 * as {@link CommandStageHostFlowSpec} in one box. The box is built by the production container chain
 * from the installation's one {@code BoxEquipment} (the run support's {@code ContainerEnvironmentFactory}),
 * and the command reaches it only through the round environment's {@code exec}: the spec hands the run
 * no docker seam of its own (design D2; F82 of tighten-spec-contract).
 *
 * <p>Read from the effects only the real flow produces: each round's harvest (its snapshot commit) and
 * its round commit on the task branch, with the state file the round commit carries. The fake agent
 * runs inside the box, so a model invocation is read from the round record that names its model: the
 * agent stage's carries the fake agent's, the command stage's none.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class CommandStageContainerE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    Path cloneDir
    String taskId = 'CTN-COMMAND-1'

    def setupSpec() {
        gitea.start()
    }

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'command-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(cloneDir)
        def originUrl = gitea.createRepository("command-stage-${System.nanoTime()}")
        addRemote(cloneDir, 'origin', originUrl)
        gitExitCode(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
    }

    def cleanup() {
        ContainerE2eDocker.removeTaskObjects(taskId)
    }

    // Scenario: executor/command-executor / Container round speaks the same protocol
    // Scenario: executor/command-executor / Command effects are the round product
    // Scenario: executor/command-executor / Flaky command retries with its finding in feedback
    // FR9, NFR-R1, M1, M2, G2: the same flaky command stage as the host flow, in a box — harvested and
    // committed per round, retried with its finding, classified exactly as on the host
    def "FR9, NFR-R1, G2: in container mode a flaky command stage retries with its finding, each round's effects harvested and committed, classified as on the host"() {
        given: 'a container-mode runner over the fake-agent sandbox image'
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def sandbox = new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def git = TaskGitFixture.real()
        def runner = new ContainerGitModeRunner(
                newAssembly(factoryProps), git, ContainerSupportFixture.real(git.epochs(), sandbox, factoryProps),
                LiveConsoleIO.onStdout())
        def segments = [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), CommandStagePipeline.stages())
        ]
        def records = new CommandStageRecords(this, cloneDir, "gnomish/${taskId}")

        when:
        runner.run(new RunOrder(cloneDir, null, CommandStagePipeline.law(), false), segments,
                new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                TaskState.atStageStart('build'))

        then: 'each command round was harvested from the box like an agent round'
        [0, 1].every { int round -> snapshotOf('build', round) }

        and: "the first round's harvest and commit carry the marker the failing command wrote, and no build result"
        tree(snapshotOf('build', 0)).contains(CommandStagePipeline.MARKER)
        !tree(snapshotOf('build', 0)).contains(CommandStagePipeline.RESULT)
        records.treeOf('build', 0).contains(CommandStagePipeline.MARKER)
        !records.treeOf('build', 0).contains(CommandStagePipeline.RESULT)

        and: "the second round ran in the same working copy: it saw the marker, passed, and its harvest and commit carry the result"
        tree(snapshotOf('build', 1)).containsAll([
            CommandStagePipeline.MARKER,
            CommandStagePipeline.RESULT
        ])
        records.treeOf('build', 1).containsAll([
            CommandStagePipeline.MARKER,
            CommandStagePipeline.RESULT
        ])

        and: "the first round is classified as on the host: a quality failure whose one finding is the host's, word for word"
        records.attempt('build', 0).get('result').asText() == 'qualityFailure'
        records.failedFindings('build', 0) == [CommandStagePipeline.FINDING]
        records.checkRefs('build', 0) == CommandStagePipeline.EXECUTOR_CHECK

        and: "the second round opened on that record, the prior feedback its request is built from; only it ran the verify chain"
        records.failedFindings('build', 0, 1) == [CommandStagePipeline.FINDING]
        records.checkRefs('build', 1) == CommandStagePipeline.VERIFY_CHECK
        records.attempt('build', 1).get('result').asText() == 'passed'

        and: "both command rounds are the built-in's: no model used, wall time recorded"
        records.isCommandRound('build', 0)
        records.isCommandRound('build', 1)

        and: "the only model the run used is the agent stage's: the fake agent's"
        with(records.participant('review', 0)) {
            get('executor').asText() == 'agent-cli'
            get('byModel').fieldNames().toList() == ['claude-fake-main-1']
        }

        and: 'the run completed: the tip is cleaned'
        !gitOutput(cloneDir, 'ls-tree', '-r', '--name-only', "gnomish/${taskId}").contains('.gnomish-task/')
    }

    /** The harvest commit of {@code stage}'s round {@code round}; exactly one exists. */
    private String snapshotOf(String stage, int round) {
        def commits = gitOutput(cloneDir, 'log', "gnomish/${taskId}", '--format=%H', '--grep',
                "^gnomish: snapshot ${stage}#${round} [0-9a-f]*\$").readLines().findAll {
                    !it.isBlank()
                }
        assert commits.size() == 1
        commits[0]
    }

    private List<String> tree(String commit) {
        gitOutput(cloneDir, 'ls-tree', '-r', '--name-only', commit).readLines()
    }
}
