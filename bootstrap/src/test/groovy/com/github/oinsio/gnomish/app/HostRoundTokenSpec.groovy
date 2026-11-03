package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskBackend
import com.github.oinsio.gnomish.adapter.git.MidRoundPushRounds
import com.github.oinsio.gnomish.adapter.git.VirtualTimeGitRetries
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.TaskGit
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
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21 of define-executor-contract (design D13, D14): on the real host git flow — the production
 * task git bundle, whose round-source decoration mints the round token over the task worktree's
 * tip, the production assembly through {@link GitModeRunner} — every round's record carries the
 * tip its round opened on, so the run's round cell held a token in host git mode. Two rounds of one
 * stage (a command check that fails once) carry two different tokens, each the commit the round
 * commit sits on.
 */
class HostRoundTokenSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    static final String TASK = 'PROJ-1'

    @TempDir
    Path tempDir

    Path cloneDir
    RegisteredClone registeredClone

    def setup() {
        cloneDir = initPlainWorkingRepo(tempDir, 'my-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        commit(cloneDir, '.gnomish/instructions.md', 'build it\n')
        addRemote(cloneDir, 'origin', initBareRepo(tempDir, 'origin.git').toString())
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
    }

    /** One stage whose command check fails on its first run and passes on every later one. */
    private StageDefinition stage() {
        Path marker = tempDir.resolve('checked-once')
        def check = new VerifyCheck.Command("test -f '${marker}' || { touch '${marker}'; exit 1; }".toString())
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [check],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    /** The production task git bundle with the production host round decoration. */
    private static TaskGit taskGit() {
        def runner = new GitProcessRunner()
        GitTaskBackend.taskGit(runner, VirtualTimeGitRetries.gitInfrastructure(), new VirtualClock(), { RoundEnvironmentSource rounds ->
            new MidRoundPushRounds(rounds, runner, new VirtualClock())
        }, new ClaimEpochBook())
    }

    /** The {@code state.json} the given commit of the task branch carries, parsed. */
    private Map stateAt(String commit) {
        new ObjectMapper().readValue(gitOutput(cloneDir, 'show', commit + ':.gnomish-task/state.json'), Map)
    }

    // FR21: a host git round mints its token over the worktree tip; two rounds carry distinct tokens
    // Scenario: git/git-task-persistence / Two host rounds carry distinct tokens
    def "FR21: two host rounds of one stage carry the tips they opened on, and the tokens differ"() {
        given:
        def runner = new GitModeRunner(
                newAssembly(null, new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8'),
                testProperties(agentCliBinary: FakeAgentSupport.wrapperFor('plain-round'))),
                taskGit(), registeredClone, LiveConsoleIO.onStdout())

        when:
        runner.run(
                new RunOrder(cloneDir, null, new PipelineDefinition('1', new AutonomyLimits(3), [stage()]), false),
                new TaskContext(TASK, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                TaskState.atStageStart('build'))

        then: 'the task branch holds two round commits, the second one\'s state recording both rounds'
        def branch = 'refs/heads/' + TaskIdSanitizer.branchName(TASK)
        def rounds = gitOutput(cloneDir, 'log', '--format=%H %s', '--reverse', branch).readLines()
                .findAll { it.contains(' gnomish: round build#') }
                .collect { it.substring(0, 40) }
        rounds.size() == 2
        def attempts = stateAt(rounds[1]).attempts as List<Map>
        attempts*.round == [0, 1]

        and: 'each record names its task and stage, and carries the tip its round opened on'
        attempts*.taskId == [TASK, TASK]
        attempts*.stage == ['build', 'build']
        attempts[0].roundToken == gitOutput(cloneDir, 'rev-parse', rounds[0] + '^')
        attempts[1].roundToken == rounds[0]

        and: 'the two tokens differ'
        attempts[0].roundToken != attempts[1].roundToken
    }
}
