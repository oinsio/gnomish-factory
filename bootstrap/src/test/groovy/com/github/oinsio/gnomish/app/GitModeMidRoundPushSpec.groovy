package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches
import com.github.oinsio.gnomish.adapter.git.GitTaskStore
import com.github.oinsio.gnomish.adapter.git.GitTaskWorktrees
import com.github.oinsio.gnomish.adapter.git.MidRoundPushRounds
import com.github.oinsio.gnomish.adapter.git.VirtualTimeGitRetries
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.Logger
import spock.lang.Specification
import spock.lang.TempDir

/**
 * M1, UX1 of wire-host-mid-round-push: the wired whole. A real git-mode host run over a local
 * bare remote, with the agent binary standing in for a gnome that commits mid-round through a
 * Bash tool: the commit lands on origin when the next progress event is observed — BEFORE the
 * round closes — and the healthy run stays silent on the operator plane (zero WARN/ERROR).
 *
 * <p>The gnome script proves the "before the round closes" half itself: after emitting the
 * post-commit progress event it polls the bare remote until the pushed tip appears, records what
 * it saw, and only then emits the round's result event. A recorded tip equal to the commit is
 * therefore an observation made strictly inside the round.
 */
class GitModeMidRoundPushSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    Path cloneDir
    Path bareRepo
    RegisteredClone registeredClone

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        commit(cloneDir, '.gnomish/instructions.md', 'build it\n')
        bareRepo = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bareRepo.toString())
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    /**
     * The gnome: the committed {@code agent-gnome-mid-round-commit} stand-in, a stream-json-emitting
     * agent that commits in its cwd (the task worktree) between two tool events, then polls the bare
     * remote — whose path the spec writes beside the per-run link — for the pushed tip and records
     * the observation before closing the round with its result event.
     */
    private Path gnome() {
        Path gnome = StandIn.recording(tempDir, 'agent-gnome-mid-round-commit')
        StandIn.beside(gnome, 'bare-repo').toFile().text = bareRepo.toString()
        gnome
    }

    /** The production TaskGit shape: real git backend plus the real mid-round push decoration. */
    private static TaskGit taskGit() {
        def runner = new GitProcessRunner()
        new TaskGit(
                new GitTaskStore(runner, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure(), new VirtualClock()),
                new GitTaskBranches(runner, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure()),
                new GitTaskWorktrees(runner, ClaimEpochSource.NONE), { RoundEnvironmentSource rounds ->
                    new MidRoundPushRounds(rounds, runner, new VirtualClock())
                }, new ClaimEpochBook())
    }

    // M1: gnome commit mid-round -> next progress event -> the remote tip equals the new commit,
    // observed by the gnome itself before it closes the round.
    // UX1 (5.2): the same healthy run produces zero WARN/ERROR after startup.
    def "a gnome commit mid-round reaches origin before the round closes, silently"() {
        given:
        Path gnome = gnome()
        Path observedRemoteTip = StandIn.beside(gnome, 'observed-tip')
        Path committedTip = StandIn.beside(gnome, 'committed-tip')
        def properties = testProperties(agentCliBinary: gnome.toString())
        def output = new ByteArrayOutputStream()
        def runner = new GitModeRunner(
                newAssembly(null, new PrintStream(output, true, 'UTF-8'), properties), taskGit(), registeredClone,
                LiveConsoleIO.onStdout())
        def operatorPlane = LogCaptureSupport.attach(Logger.ROOT_LOGGER_NAME, Level.WARN)

        when:
        def originalOut = System.out
        System.out = new PrintStream(output, true, 'UTF-8')
        try {
            runner.run(
                    new RunOrder(cloneDir, null,
                    new PipelineDefinition('1', new AutonomyLimits(3), [stage()]),
                    false),
                    new TaskContext('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                    TaskState.atStageStart('build'))
        } finally {
            System.out = originalOut
        }

        then: 'the gnome observed its commit on the remote before emitting the round result (M1)'
        Files.exists(observedRemoteTip)
        Files.readString(observedRemoteTip) == Files.readString(committedTip).trim()

        and: 'the healthy run put nothing on the operator plane (UX1): zero WARN/ERROR'
        operatorPlane.list.findAll {
            it.level.isGreaterOrEqual(Level.WARN)
        }.empty

        cleanup:
        operatorPlane.detach()
    }
}
