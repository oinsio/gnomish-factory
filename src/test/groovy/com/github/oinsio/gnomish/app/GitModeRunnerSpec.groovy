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
 * FR6, FR7, UX1 of add-git-workflow (task 4.4): {@link GitModeRunner} drives the fresh-run
 * git-mode path — prune, print the branch/worktree banner upfront, create the task branch and
 * worktree via {@code GitTaskRepository#createTask}, run the pipeline against the worktree (not
 * the clone), and clean up on completion.
 */
class GitModeRunnerSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
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

    private static TaskContext context(String taskId = 'PROJ-1') {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }

    private static TaskState initialState() {
        TaskState.atStageStart('build')
    }

    /**
     * A {@link GitModeRunner} wired with the interactive console adapters (so a scripted bare
     * Enter drives one round to completion without a real agent CLI), reading from {@code in}
     * and writing to {@code out}.
     */
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
        worktreesRoot.resolve('my-project').resolve(taskId)
    }

    // FR6, FR7, UX1: the banner prints before the pipeline runs, naming the deterministic branch
    // and worktree path. printBanner writes directly to System.out (the process's own console,
    // not the injected interactive console adapter), so this spec redirects System.out itself.
    def "run() prints the branch and worktree banner before the pipeline runs"() {
        given:
        def originalOut = System.out
        def out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')
        def runner = newRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')), System.out)

        when:
        runner.run(cloneDir, null, pipeline(), context(), initialState(), RunArguments.InteractiveMode.ALL)

        then:
        def output = out.toString('UTF-8')
        def lines = output.readLines()
        def branchLine = lines.find { it.contains('git mode: branch') }
        def worktreeLine = lines.find { it.contains('git mode: worktree') }
        branchLine != null
        branchLine.contains('gnomish/PROJ-1')
        worktreeLine != null
        worktreeLine.contains(expectedWorktree('PROJ-1').toString())
        lines.indexOf(branchLine) < lines.indexOf(worktreeLine)

        cleanup:
        System.out = originalOut
    }

    // FR7: the branch and worktree are actually created, and the run's changes land in the
    // worktree — the clone's own working copy is never touched
    def "run() creates the branch and worktree, and leaves the clone's working copy untouched"() {
        given:
        def out = new ByteArrayOutputStream()
        def runner = newRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')),
                new PrintStream(out, true, 'UTF-8'))
        def cloneStatusBefore = gitRunner.run(cloneDir, 'status', '--porcelain').stdout()

        when:
        runner.run(cloneDir, null, pipeline(), context('PROJ-2'), initialState(), RunArguments.InteractiveMode.ALL)

        then: 'the branch exists'
        gitRunner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/PROJ-2').exitCode() == 0

        and: "the clone's working copy is untouched"
        gitRunner.run(cloneDir, 'status', '--porcelain').stdout() == cloneStatusBefore
    }

    // FR6, FR15/M4: Completed removes the worktree (branch stays, history preserved)
    def "run() removes the worktree once the task completes, branch and history stay"() {
        given:
        def out = new ByteArrayOutputStream()
        def runner = newRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')),
                new PrintStream(out, true, 'UTF-8'))

        when:
        runner.run(cloneDir, null, pipeline(), context('PROJ-3'), initialState(), RunArguments.InteractiveMode.ALL)

        then:
        !Files.exists(expectedWorktree('PROJ-3'))
        gitRunner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/PROJ-3').exitCode() == 0
    }

    // FR6: run() prunes stale worktree registrations before creating the task's own worktree — a
    // prior worktree at the same deterministic path, deleted by something other than `git worktree
    // remove` (e.g. an operator's rm -rf), would otherwise collide with a fresh `git worktree add`
    // at that exact path ("missing but already registered worktree") without the prune call.
    def "run() prunes a stale worktree registration left at the same deterministic path"() {
        given: 'a worktree for this exact taskId was registered, then deleted without git worktree remove'
        def worktree = expectedWorktree('PROJ-7')
        Files.createDirectories(worktree.getParent())
        gitRunner.run(cloneDir, 'worktree', 'add', worktree.toString(), 'HEAD')
        gitRunner.run(cloneDir, 'worktree', 'remove', '--force', worktree.toString())
        assert gitRunner.run(cloneDir, 'rev-parse', 'HEAD').exitCode() == 0

        // Simulate the exact "missing but already registered" state git worktree remove would
        // never itself leave behind, by re-creating the directory-only registration bypassing git.
        gitRunner.run(cloneDir, 'worktree', 'add', worktree.toString(), 'HEAD')
        worktree.toFile().deleteDir()
        assert !Files.exists(worktree)

        def runner = newRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')), System.out)

        when: 'a fresh run for the same taskId needs the identical worktree path'
        runner.run(cloneDir, null, pipeline(), context('PROJ-7'), initialState(), RunArguments.InteractiveMode.ALL)

        then: 'the stale registration was pruned first, so the fresh worktree add succeeded'
        noExceptionThrown()
        gitRunner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/PROJ-7').exitCode() == 0
    }

    // FR7: a fresh run whose taskId already has a branch is a usage error, not a silent resume
    def "run() throws UsageException when the task branch already exists"() {
        given:
        gitRunner.run(cloneDir, 'branch', 'gnomish/PROJ-4', 'HEAD')
        def runner = newRunner(new ByteArrayInputStream(new byte[0]), System.out)

        when:
        runner.run(cloneDir, null, pipeline(), context('PROJ-4'), initialState(), RunArguments.InteractiveMode.ALL)

        then:
        thrown(UsageException)
    }

    // FR7, design D7: an unresolved --base is a usage error, not a resumable condition
    def "run() throws UsageException when --base does not resolve"() {
        given:
        def runner = newRunner(new ByteArrayInputStream(new byte[0]), System.out)

        when:
        runner.run(cloneDir, 'does-not-exist', pipeline(), context('PROJ-5'), initialState(),
                RunArguments.InteractiveMode.ALL)

        then:
        thrown(UsageException)

        and: 'no branch was created for the failed attempt'
        gitRunner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/PROJ-5').exitCode() != 0
    }

    // FR6, FR8, task 4.7: a broken durability guarantee (round-boundary violation, design D12) is
    // recorded as an Aborted outcome through GitTaskRepository, and the worktree is kept
    // unconditionally for forensics (design D6) rather than removed.
    def "run() records an Aborted outcome and keeps the worktree when the round-boundary protocol is violated"() {
        given: 'the deterministic worktree path is pre-registered on the wrong branch — a stand-in'
        and: 'for a gnome that checked out another branch, which GitAttemptPersistence must catch'
        def taskId = 'PROJ-6'
        def worktree = expectedWorktree(taskId)
        Files.createDirectories(worktree.getParent())
        gitRunner.run(cloneDir, 'worktree', 'add', '-b', 'not-the-task-branch', worktree.toString())
        def runner = newRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')), System.out)

        when:
        runner.run(cloneDir, null, pipeline(), context(taskId), initialState(), RunArguments.InteractiveMode.ALL)

        then:
        def ex = thrown(AbortedException)
        ex.outcome() != null

        and: 'the outcome is durably recorded in the worktree HEAD (GitTaskRepository commits wherever'
        and: 'the worktree currently is, unlike the round-boundary check that caused this Aborted)'
        def taskJson = gitRunner.run(worktree, 'show', 'HEAD:.gnomish-task/task.json').stdout()
        taskJson.contains('"aborted"')

        and: 'the worktree is kept, unconditionally, for forensics'
        Files.isDirectory(worktree)
    }
}
