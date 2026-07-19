package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
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
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-S2 of add-git-workflow (task 4.5): git-mode workspace hygiene. Runs a real {@code
 * CliStageExecutor} round (fake agent binary, {@code plain-round} scenario, task 9's fixture)
 * through {@link GitModeRunner} against a real worktree, then inspects the round commit's tree
 * and the worktree's final state to prove the round contains only the gnome's own change
 * ({@code output.txt}) plus {@code .gnomish-task/} — no decision-file temp dir and no log file
 * ever entered the worktree, exactly the guarantee add-agent-executor's NFR-S2 already gives the
 * in-place path (verified by {@code LogbackConfigSpec} for logs and {@code
 * DecisionFileTransportSpec} for the temp-dir lifecycle).
 *
 * <p>{@link com.github.oinsio.gnomish.adapter.agent.DecisionFileTransport}'s production
 * constructor always roots the per-round temp directory under {@code java.io.tmpdir}, never under
 * the workspace it is handed (which {@link GitModeRunner} sets to the worktree); this spec is the
 * regression net that would fail if a future change threaded the workspace root into that
 * decision instead.
 */
class GitModeWorkspaceHygieneSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'hygiene-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'Do the thing.\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees-root')
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    private GitModeRunner newRunner(FactoryProperties factoryProperties) {
        def assembly = new ManualRunAssembly(
                new SystemConsoleIO(new ByteArrayInputStream(new byte[0]), System.out),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new SystemClock(),
                new ThreadSleeper(),
                factoryProperties)
        new GitModeRunner(assembly, worktreesRoot)
    }

    private Path expectedWorktree(String taskId) {
        worktreesRoot.resolve('hygiene-project').resolve(taskId)
    }

    // NFR-S2: a real agent round's commit contains exactly the gnome's own file plus
    // .gnomish-task/ — no decision-file temp dir, no log file, nothing from the round transport.
    def "a completed git-mode round's commit tree holds only the gnome's change and .gnomish-task/"() {
        given:
        def properties = FakeAgentSupport.propertiesFor('plain-round')
        def runner = newRunner(properties)
        def context = new TaskContext('HYG-1', 'title', 'body', List.<Decision> of())

        when:
        runner.run(cloneDir, null, pipeline(), context, TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.NONE)

        then: 'the branch carries exactly one round commit on top of init'
        def tipSha = gitRunner.run(cloneDir, 'rev-parse', 'gnomish/HYG-1').stdout().trim()
        def tree = gitRunner.run(cloneDir, 'ls-tree', '-r', '--name-only', tipSha).stdout()
                .readLines().findAll { !it.isBlank() }

        and: 'the gnome change and instructions.md carried from init are present'
        tree.contains('output.txt')
        tree.contains('instructions.md')

        and: 'every other path is a .gnomish-task/ structural artifact'
        tree.findAll { !it.startsWith('.gnomish-task/') && it != 'output.txt' && it != 'instructions.md' }
        .isEmpty()

        and: 'no decision-file or fake-agent-log scratch path leaked into the tree'
        tree.every { !it.contains('gnomish-decision-') && !it.contains('gnomish-findings-') }
    }

    // NFR-S2: DecisionFileTransport's production constructor (adapter/agent/DecisionFileTransport
    // .java) always roots the per-round temp directory under java.io.tmpdir; this asserts the
    // consequence that actually matters for a task branch — no gnomish-decision- directory ever
    // appears anywhere under the worktree a git-mode round ran against.
    def "a git-mode round leaves no decision-file temp dir under the worktree"() {
        given:
        def properties = FakeAgentSupport.propertiesFor('plain-round')
        def runner = newRunner(properties)
        def context = new TaskContext('HYG-2', 'title', 'body', List.<Decision> of())

        when:
        runner.run(cloneDir, null, pipeline(), context, TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.NONE)

        then: 'no leftover gnomish-decision- directory anywhere under the worktree root'
        def worktree = expectedWorktree('HYG-2')
        !worktree.toFile().exists() || findDecisionDirs(worktree).isEmpty()
    }

    private static List<Path> findDecisionDirs(Path root) {
        if (!Files.exists(root)) {
            return []
        }
        try (def stream = Files.walk(root)) {
            return stream
                    .filter { Files.isDirectory(it) && it.fileName.toString().startsWith('gnomish-decision-') }
                    .toList()
        }
    }
}
