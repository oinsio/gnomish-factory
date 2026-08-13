package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.adapter.environment.AdapterBinding
import com.github.oinsio.gnomish.adapter.environment.EnvironmentLease
import com.github.oinsio.gnomish.adapter.environment.Segment
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR4, FR5, FR21, FR23 of add-sandbox-core (design D15, D17): the sandboxed
 * {@link RoundEnvironmentSource} opens a round in the task's leased container
 * environment, wires the in-branch decision transport (path and env
 * fragment), exposes the rate-limited mid-round harvest listener, and closes
 * the round with the snapshot-commit protocol.
 */
class SandboxRoundEnvironmentSourceSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'SBX-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)
    static final String STAGE = 'build'

    @TempDir
    Path tempDir

    Path cloneDir
    AttemptCommitRef attemptRef = new AttemptCommitRef()
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
            new Segment(AdapterBinding.CONTAINER, [stage])
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
                new TaskContext(TASK, 'title', 'body', []),
                stageDefinition(), new DirectoryWorkspace(tempDir), attempt, [])
    }

    private SandboxRoundEnvironmentSource source() {
        new SandboxRoundEnvironmentSource(lease, new GitProcessRunner(), cloneDir, TASK, attemptRef, new VirtualClock())
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

    def "FR23: decisionFilePath names this round's branch-relative decision path"() {
        when:
        def round = source().openRound(request(1))

        then:
        round.decisionFilePath() != null
        round.decisionFilePath().toString() == '.gnomish-task/decisions/' + STAGE + '-a1.json'
    }

    def "FR23: decisionEnvFragment carries the decision path under the fixed env var name"() {
        when:
        def round = source().openRound(request(2))

        then:
        def fragment = round.decisionEnvFragment()
        !fragment.isEmpty()
        fragment == [GNOMISH_DECISION_FILE: '.gnomish-task/decisions/' + STAGE + '-a2.json']
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
                '.gnomish-task/decisions/' + STAGE + '-a1.json')
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
        def round = source().openRound(request(1))
        new File(((LocalBoxEnvironment) round.environment()).workingCopy.toFile(), 'work.txt').text = 'gnome work'

        when:
        round.closeRound()

        then: 'the attempt commit ref now carries the harvested snapshot commit'
        def attempt = attemptRef.required()
        gitOutput(cloneDir, 'log', '-1', '--format=%s', attempt) == 'gnomish: snapshot ' + STAGE + '#1'
        gitOutput(cloneDir, 'show', attempt + ':work.txt') == 'gnome work'
    }
}
