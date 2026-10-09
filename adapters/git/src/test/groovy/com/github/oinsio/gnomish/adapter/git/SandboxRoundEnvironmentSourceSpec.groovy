package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.RoundToken
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR4, FR5, FR21, FR23 of add-sandbox-core (design D15, D17): the sandboxed
 * {@link RoundEnvironmentSource} opens a round in the task's leased container
 * environment, wires the in-branch decision transport (path and env
 * fragment), exposes the rate-limited mid-round harvest listener, and closes
 * the round with the snapshot-commit protocol. FR13 of make-checkpoint-gate-durable (D10):
 * the round's token is the tip it opened on, opened in the run's {@link CurrentRound}, and the
 * snapshot closes that same round in it.
 */
class SandboxRoundEnvironmentSourceSpec extends Specification implements BareGitRepoFixture, FailingSubcommandGitFixture {

    static final String TASK = 'SBX-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)
    static final String STAGE = 'build'

    @TempDir
    Path tempDir

    Path cloneDir
    CurrentRound rounds = new CurrentRound()
    EnvironmentLease lease

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)

        def stage = stageDefinition()
        def boxCounter = 0
        lease = new EnvironmentLease({
            ->
            def boxRoot = Files.createDirectories(tempDir.resolve('box' + (boxCounter++)))
            new LocalBoxEnvironment(cloneDir, boxRoot)
        },
        BRANCH,
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage])
        ])
    }

    private static StageDefinition stageDefinition() {
        new StageDefinition(
                STAGE, 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private StageExecutor.Request request(int attempt = 1) {
        new StageExecutor.Request(
                new TaskContext(TASK, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                stageDefinition(), new DirectoryWorkspace(tempDir), attempt, [])
    }

    private SandboxRoundEnvironmentSource source() {
        new SandboxRoundEnvironmentSource(lease, new GitProcessRunner(), cloneDir, TASK, rounds, new VirtualClock())
    }

    private String branchTip() {
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
    }

    private String tokenPath(int attempt) {
        '.gnomish-task/decisions/' + STAGE + '-a' + attempt + '-' + branchTip() + '.json'
    }

    def "FR4: openRound returns a non-null round with a real, materialized environment"() {
        when:
        def round = source().openRound(request())

        then:
        round != null
        round.environment() != null
        round.environment() instanceof LocalBoxEnvironment
        // the leased environment is genuinely materialized: the working copy exists on disk
        ((LocalBoxEnvironment) round.environment()).workingCopy.toFile().isDirectory()
    }

    def "FR13, FR23: the decision path and its env fragment name this round's token path, branch-relative"() {
        when:
        def round = source().openRound(request(2))

        then:
        round.decisionFilePath().toString() == tokenPath(2)
        round.decisionEnvFragment() == [GNOMISH_DECISION_FILE: tokenPath(2)]
    }

    def "FR13: each round records the tip it opened on as the run's token, so a repeated key names a new path"() {
        given: 'a first round records the tip it opened on, then closes with its snapshot commit'
        def openTip = branchTip()
        def first = source().openRound(request(0))
        assert rounds.opened() == RoundToken.of(openTip)
        def firstPath = first.decisionFilePath().toString()
        first.closeRound()

        when: 'the same stage and attempt open again on the moved tip'
        def second = source().openRound(request(0))

        then: 'the token follows the tip, so the same key names a different file'
        rounds.opened() == RoundToken.of(branchTip())
        second.decisionFilePath().toString() == tokenPath(0)
        second.decisionFilePath().toString() != firstPath
    }

    def "FR13: openRound refuses, recording no token, when the branch tip cannot be resolved"() {
        given: 'the tip read fails while the environment still materializes'
        def failing = new GitProcessRunner(gitFailingOn(tempDir, 'rev-parse').toString())

        when:
        new SandboxRoundEnvironmentSource(lease, failing, cloneDir, TASK, rounds, new VirtualClock())
                .openRound(request(1))

        then:
        thrown(BranchTipUnavailableException)

        when:
        rounds.opened()

        then:
        thrown(IllegalStateException)
    }

    def "FR5: roundListener is the sandboxed mid-round harvest listener, not the default no-op"() {
        when:
        def round = source().openRound(request())

        then:
        round.roundListener() != null
        round.roundListener() instanceof MidRoundHarvestListener
    }

    def "FR23: readDecision returns empty when the agent wrote no decision file"() {
        given:
        def round = source().openRound(request())

        expect:
        round.readDecision().isEmpty()
    }

    def "FR23: readDecision returns the exact content the agent wrote at this round's decision path"() {
        given:
        def round = source().openRound(request(1))
        def decisionFile = new File(
                ((LocalBoxEnvironment) round.environment()).workingCopy.toFile(),
                tokenPath(1))
        decisionFile.parentFile.mkdirs()
        decisionFile.text = '{"question":"which db?"}'

        when:
        def decision = round.readDecision()

        then:
        decision.isPresent()
        decision.get() == '{"question":"which db?"}'
    }

    def "FR21: closeRound snapshots the environment and records the harvested attempt commit"() {
        given:
        def openTip = branchTip()
        def round = source().openRound(request(1))
        new File(((LocalBoxEnvironment) round.environment()).workingCopy.toFile(), 'work.txt').text = 'gnome work'

        when:
        round.closeRound()

        then: 'the cell now holds the round closed by the harvested snapshot commit, under the token it opened with'
        def closed = rounds.closed()
        closed.token() == RoundToken.of(openTip)
        def attempt = closed.attemptCommit()

        and: 'FR15 of make-checkpoint-gate-durable: its subject names the token the round opened with'
        gitOutput(cloneDir, 'log', '-1', '--format=%s', attempt) == 'gnomish: snapshot ' + STAGE + '#1 ' + openTip
        gitOutput(cloneDir, 'show', attempt + ':work.txt') == 'gnome work'
    }
}
