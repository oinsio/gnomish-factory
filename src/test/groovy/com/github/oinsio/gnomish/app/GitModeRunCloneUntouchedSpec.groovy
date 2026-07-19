package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
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
 * FR7, G4 of add-git-workflow (task 6.4): a git-mode {@code run} never mutates the clone given
 * via {@code --dir} — all work happens in a separate worktree (design D6, D7). {@link
 * GitModeRunnerSpec} already proves the branch is created and {@code git status --porcelain}
 * stays empty; this spec goes further, snapshotting the clone's current branch, HEAD sha, index
 * (via a full tracked-file content hash, not just porcelain), reflog, and worktree/branch lists
 * before and after a full round, on top of asserting the round's actual work (branch commit,
 * worktree files) really landed — so the guarantee is verified end-to-end, not true by
 * construction.
 */
class GitModeRunCloneUntouchedSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone-untouched-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'Do the thing.\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        // Operator had a feature branch checked out, not main/master, before starting the task.
        gitRunner.run(cloneDir, 'checkout', '-b', 'operator-feature-branch')
        Files.writeString(cloneDir.resolve('operator-work.txt'), 'unrelated uncommitted change\n')
        worktreesRoot = tempDir.resolve('worktrees-root')
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    private GitModeRunner newRunner(InputStream input, PrintStream output) {
        def assembly = new ManualRunAssembly(
                new SystemConsoleIO(input, output),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new SystemClock(),
                new ThreadSleeper(),
                new FactoryProperties('test-instance', null, null))
        new GitModeRunner(assembly, worktreesRoot)
    }

    private Path expectedWorktree(String taskId) {
        worktreesRoot.resolve('clone-untouched-project').resolve(taskId)
    }

    /** Content hash of every tracked file plus its path, so a rewritten-but-same-content file
     * or an index change with no working-tree diff would still be caught (porcelain alone
     * would miss both). */
    private String trackedContentFingerprint() {
        gitRunner.run(cloneDir, 'ls-tree', '-r', 'HEAD').stdout() +
                '|' + gitRunner.run(cloneDir, 'diff', '--cached').stdout()
    }

    /**
     * The operator's own pre-existing branches only ({@code master}, {@code
     * operator-feature-branch}) — excludes {@code gnomish/*}, since git-mode's task branch is
     * expected to appear in the shared refs database (worktrees share one {@code .git}, design
     * D6/D7: it is the worktree's working copy/index/current-branch that must stay untouched,
     * not the refs namespace). Isolating this from the full {@code refs/heads/} listing keeps
     * the invariant precise instead of accidentally failing on the very ref FR7 says is fine.
     */
    private String operatorBranchRefs() {
        gitRunner.run(cloneDir, 'for-each-ref', 'refs/heads/master', 'refs/heads/operator-feature-branch').stdout()
    }

    private Map snapshot() {
        [
            branch      : gitRunner.run(cloneDir, 'symbolic-ref', '--short', 'HEAD').stdout().trim(),
            head        : gitRunner.run(cloneDir, 'rev-parse', 'HEAD').stdout().trim(),
            porcelain   : gitRunner.run(cloneDir, 'status', '--porcelain').stdout(),
            content     : trackedContentFingerprint(),
            operatorBranches: operatorBranchRefs(),
            worktrees   : gitRunner.run(cloneDir, 'worktree', 'list', '--porcelain').stdout(),
            reflog      : gitRunner.run(cloneDir, 'reflog', 'show', '--no-abbrev', 'HEAD').stdout(),
            uncommitted : Files.readString(cloneDir.resolve('operator-work.txt')),
        ]
    }

    // FR7, G4: the operator's own checked-out branch, HEAD, index, working copy, and reflog are
    // byte-for-byte/ref-for-ref identical after a full git-mode round — not just "no porcelain
    // diff" — while the actual work (branch + commit + files) really landed in the worktree.
    def "a full git-mode round leaves the clone's branch, HEAD, index, and working copy untouched while the work lands in the worktree"() {
        given:
        def out = new ByteArrayOutputStream()
        def runner = newRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')),
                new PrintStream(out, true, 'UTF-8'))
        def before = snapshot()

        when:
        runner.run(cloneDir, null, pipeline(), taskContext('CLONE-1'), TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.ALL)

        then: 'the work actually landed: the task branch and its round commit exist'
        gitRunner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/CLONE-1').exitCode() == 0
        def taskTip = gitRunner.run(cloneDir, 'rev-parse', 'gnomish/CLONE-1').stdout().trim()
        taskTip != before.head

        and: 'the round content is in the worktree/branch tree, not the clone working copy'
        def tree = gitRunner.run(cloneDir, 'ls-tree', '-r', '--name-only', taskTip).stdout()
        tree.contains('.gnomish-task/task.json') || tree.contains('output.txt') || tree.contains('instructions.md')

        and: "the clone's own checked-out branch and HEAD never moved"
        def after = snapshot()
        after.branch == before.branch
        after.branch == 'operator-feature-branch'
        after.head == before.head

        and: "the clone's index/working copy is unchanged"
        after.porcelain == before.porcelain
        after.content == before.content
        after.uncommitted == before.uncommitted

        and: "the operator's own pre-existing branches (master, operator-feature-branch) did not move"
        after.operatorBranches == before.operatorBranches

        and: 'the reflog for HEAD recorded no new entries — no checkout, no commit, no reset ran against the clone'
        after.reflog == before.reflog

        and: "the clone's own worktree listing entry is unchanged (still just itself on operator-feature-branch)"
        after.worktrees == before.worktrees
    }

    // FR7, G4: same guarantee holds even when the round aborts on a round-boundary violation —
    // the clone must stay untouched regardless of the pipeline's outcome.
    def "an aborted git-mode round still leaves the clone's branch, HEAD, and working copy untouched"() {
        given: 'a worktree pre-registered on the wrong branch, forcing GitAttemptPersistence to abort'
        def taskId = 'CLONE-2'
        def worktree = expectedWorktree(taskId)
        Files.createDirectories(worktree.getParent())
        gitRunner.run(cloneDir, 'worktree', 'add', '-b', 'not-the-task-branch', worktree.toString())
        def before = snapshot()
        def runner = newRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')), System.out)

        when:
        runner.run(cloneDir, null, pipeline(), taskContext(taskId), TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.ALL)

        then:
        thrown(AbortedException)

        and: "the clone's own branch/HEAD/working copy are untouched despite the failure"
        def after = snapshot()
        after.branch == before.branch
        after.head == before.head
        after.porcelain == before.porcelain
        after.uncommitted == before.uncommitted
    }

    private static TaskContext taskContext(String taskId) {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }
}
