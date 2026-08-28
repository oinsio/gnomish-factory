package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.BranchStateResult
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12b of split-into-modules: {@code GitTaskBranches} is the branch-side delegation facade the
 * application layer binds as {@code TaskBranchGit}. It holds no logic — every method forwards to
 * the collaborator that already implemented it — so this spec asserts exactly that: each port
 * method reaches its collaborator and hands back what the collaborator produced.
 *
 * <p>Task 8.1 of split-into-modules is why it exists as a spec of its own. Per-module mutation
 * scoping means a module's classes must be covered by that module's own specs, and until now the
 * only thing driving this facade was the composition root's run-assembly integration suites in
 * {@code :bootstrap} — a different module's test task, invisible to {@code :adapters:pitest}.
 */
class GitTaskBranchesSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def branches = new GitTaskBranches(runner)
    Path cloneDir
    Path worktreesRoot

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('a.txt'), 'first')
        commitAll(cloneDir, 'init')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private Path worktreeFor(String taskId) {
        worktreesRoot.resolve('clone').resolve(taskId)
    }

    private void seedTask(String taskId) {
        new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE).createTask(new TaskContext(taskId, 'T', 'B', []), null, TaskState.atStageStart('implement'))
        def trace = new ToolTrace(new AttemptKey(taskId, 'implement', 0), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
        new GitAttemptPersistence(runner, worktreeFor(taskId), taskId, ClaimEpochSource.NONE).persist(taskId, TaskState.atStageStart('implement'), trace)
    }

    def "harden delegates to FactoryCloneHardening — the clone's hooksPath is repointed"() {
        given:
        assert runner.run(cloneDir, 'config', '--get', 'core.hooksPath').exitCode() != 0

        when:
        branches.harden(cloneDir)

        then:
        def configured = Path.of(runner.run(cloneDir, 'config', '--get', 'core.hooksPath').stdout().trim())
        configured.fileName.toString() == FactoryCloneHardening.EMPTY_HOOKS_DIR
        Files.isDirectory(configured)
    }

    def "ensureLocalTaskBranch delegates to ContainerResumeBranch — present locally is usable, absent everywhere is not"() {
        given:
        seedTask('PROJ-1')
        runner.run(cloneDir, 'checkout', '-q', 'main')

        expect:
        branches.ensureLocalTaskBranch(cloneDir, 'PROJ-1')
        !branches.ensureLocalTaskBranch(cloneDir, 'NO-SUCH')
    }

    def "ensureLocalTaskBranch materializes the local branch from the remote-tracking ref"() {
        given: 'a clone whose task branch exists only on origin'
        def bare = initBareRepo(tempDir, 'origin.git')
        seedTask('PROJ-2')
        runner.run(cloneDir, 'remote', 'add', 'origin', bare.toString())
        runner.run(cloneDir, 'push', 'origin', 'gnomish/PROJ-2')
        runner.run(cloneDir, 'checkout', '-q', 'main')
        runner.run(cloneDir, 'branch', '-D', 'gnomish/PROJ-2')

        when:
        def usable = branches.ensureLocalTaskBranch(cloneDir, 'PROJ-2')

        then:
        usable
        runner.run(cloneDir, 'rev-parse', '--verify', '--quiet', 'refs/heads/gnomish/PROJ-2').exitCode() == 0
    }

    def "locate delegates to TaskBranchLocator and returns its verdict"() {
        given:
        seedTask('PROJ-3')

        expect:
        branches.locate(cloneDir, 'PROJ-3') instanceof BranchLocation.Local
        branches.locate(cloneDir, 'NO-SUCH') instanceof BranchLocation.NotFound
    }

    // FR1, FR2 of harden-task-branch-contract: the facade is where the located ref meets the
    // classifier, so every take, resume and reconcile path asks one question and gets one name.
    def "FR2: classifyShape names the located tip through the one classifier"() {
        given: 'a started task whose state records no round yet'
        seedTask('PROJ-9')

        expect:
        branches.classifyShape(cloneDir, 'PROJ-9') instanceof BranchShape.Created

        and: 'a branch that exists nowhere is Bare — the shape the take routes to a fresh claim'
        branches.classifyShape(cloneDir, 'NO-SUCH') instanceof BranchShape.Bare
    }

    def "FR2: a recorded park is named Parked, and a Completed tip still holding its envelope CompletedUncleaned"() {
        given:
        seedTask('PROJ-10')
        def repository = new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
        repository.recordOutcome('PROJ-10', new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'))

        expect:
        branches.classifyShape(cloneDir, 'PROJ-10') instanceof BranchShape.Parked

        and: 'the outcome commit without its cleanup behind it is the kill-window shape, not delivery'
        seedTask('PROJ-11')
        repository.recordOutcome('PROJ-11', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        branches.classifyShape(cloneDir, 'PROJ-11') instanceof BranchShape.CompletedUncleaned
    }

    def "list delegates to TaskBranchLister and returns the rows it found"() {
        given:
        seedTask('PROJ-4')

        when:
        def rows = branches.list(cloneDir)

        then:
        rows*.taskId() == ['PROJ-4']
    }

    def "readState delegates to BranchStateReader and returns its result"() {
        given:
        seedTask('PROJ-5')

        expect:
        branches.readState(cloneDir, 'PROJ-5') instanceof BranchStateResult.Found
        branches.readState(cloneDir, 'NO-SUCH') instanceof BranchStateResult.NotFound
    }

    def "readDelivered delegates to DeliveredBranchReader and returns the recovered delivery"() {
        given: 'a delivered task branch, whose tip no longer carries the state files'
        seedTask('PROJ-6')
        def finalState = TaskState.atStageStart('implement')
        new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
                .recordOutcome('PROJ-6', new TaskOutcome.Completed(finalState))

        when:
        DeliveredBranchState delivered = branches.readDelivered(cloneDir, 'PROJ-6')

        then:
        delivered.context().taskId() == 'PROJ-6'
        delivered.finalState() == finalState
    }

    def "pushBestEffort delegates to BranchPush — the branch lands on origin"() {
        given:
        def bare = initBareRepo(tempDir, 'push-origin.git')
        seedTask('PROJ-7')
        def worktree = worktreeFor('PROJ-7')
        runner.run(worktree, 'remote', 'add', 'origin', bare.toString())

        when:
        branches.pushBestEffort(worktree, 'gnomish/PROJ-7')

        then:
        runner.run(bare, 'rev-parse', '--verify', 'gnomish/PROJ-7').exitCode() == 0
    }
}
