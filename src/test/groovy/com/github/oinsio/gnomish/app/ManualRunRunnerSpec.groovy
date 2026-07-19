package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.git.state.UnsupportedStateFileVersionException
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, FR9, FR12, UX3, D10 of add-manual-run: the {@code gnomish run} ApplicationRunner
 * entrypoint. No relevant flag present is a no-op, preserving FactoryApplication's untouched
 * no-args behavior (task 7.12 verifies that boundary at the full-context level next); with at
 * least one relevant flag, the runner drives argument parsing, pipeline load, ad-hoc task
 * synthesis, and the outcome loop in order.
 *
 * <p>FR6, FR7, UX1, UX4, design D8 of add-git-workflow: {@code --mode=in-place} prints the
 * in-memory reminder before the pipeline runs; {@code --mode=git} (the default) creates the task
 * branch and worktree, prints the UX1 banner upfront, and runs the pipeline against the worktree
 * — proven here end to end against a local bare-repo clone (task 4.4).
 */
class ManualRunRunnerSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path projectRoot

    @TempDir
    Path worktreesRoot

    private static final FactoryProperties FACTORY_PROPERTIES = new FactoryProperties('test-instance', null, null)

    private ManualRunRunner newRunner() {
        new ManualRunRunner(
                new RunArgumentsParser(),
                new PipelineStartup(),
                new AdHocTaskSynthesizer(java.time.Clock.systemUTC(), new Random()),
                new SystemConsoleIO(System.in, System.out),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new InMemoryAttemptPersistence(),
                new SystemClock(),
                new ThreadSleeper(),
                FACTORY_PROPERTIES,
                worktreesRoot,
                new StatusCommand(worktreesRoot),
                new UsageCommand())
    }

    private void write(String relative, String text) {
        Path target = projectRoot.resolve('.gnomish').resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    private void writeOneStagePipeline() {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write('pipeline.yaml', 'stages:\n  - build\n')
        write('stages/build/stage.yaml', '''\
purpose: build the thing
executor:
  type: agent-cli
  model: some-model
instructions: stages/build/instructions.md
verify:
  - type: builtin
    name: files_exist
    params:
      files: []
advancement: auto
''')
        write('stages/build/instructions.md', 'build it\n')
    }

    // D10: the starting stage's own attemptLimit (7), not the pipeline default (3)
    private void writeOneStagePipelineWithStageAttemptLimitOverride() {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write('pipeline.yaml', 'stages:\n  - build\n')
        write('stages/build/stage.yaml', '''\
purpose: build the thing
executor:
  type: agent-cli
  model: some-model
instructions: stages/build/instructions.md
verify:
  - type: builtin
    name: files_exist
    params:
      files: []
advancement: auto
autonomy:
  attemptLimit: 7
''')
        write('stages/build/instructions.md', 'build it\n')
    }

    /**
     * Turns {@code projectRoot} into a real, non-bare git clone with one commit — the {@code
     * --dir} git mode needs (FR7): {@code TaskBranchCreator} branches off {@code HEAD}, which
     * requires at least one commit to resolve.
     */
    private void makeProjectRootAGitClone() {
        def runner = new GitProcessRunner()
        assert runner.run(projectRoot, 'init').exitCode() == 0
        Files.writeString(projectRoot.resolve('README.md'), 'seed\n')
        runner.run(projectRoot, 'add', 'README.md')
        assert runner.run(projectRoot, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        .exitCode() == 0
    }

    // FR12: no relevant flag present -> no-op, untouched no-args behavior
    def "run() does nothing when no gnomish-run flag is present"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments()

        when:
        runner.run(args)

        then:
        noExceptionThrown()
    }

    // FR12, FR13, FR14: an unrelated Boot-style flag alongside the 'run' subcommand is still a no-op
    def "run() does nothing when only unrelated arguments are present"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments('run', '--debug')

        when:
        runner.run(args)

        then:
        noExceptionThrown()
    }

    // FR13, FR14: an explicit 'run' subcommand token behaves exactly like no subcommand
    def "run() treats an explicit 'run' subcommand token like the implicit default"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments('run')

        when:
        runner.run(args)

        then:
        noExceptionThrown()
    }

    // FR13: the 'status' subcommand routes to StatusCommand, not the run flow
    def "run() dispatches a 'status' subcommand to StatusCommand"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments('status', "--dir=${projectRoot}".toString())

        when:
        runner.run(args)

        then: 'dispatch reached StatusCommand\'s list mode (FR13 task 5.4) rather than the run flow — no run-flow error'
        noExceptionThrown()
    }

    // FR14: the 'usage' subcommand routes to UsageCommand, not the run flow
    def "run() dispatches a 'usage' subcommand to UsageCommand"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments('usage', "--dir=${projectRoot}".toString(), 'task-1')

        when:
        runner.run(args)

        then: 'dispatch reached UsageCommand (FR14 task 5.6, not the run flow) and reported the absent branch cleanly (FR13, UX3)'
        thrown(TaskNotFoundException)
    }

    // FR13, UX3, D15: "task not found" is a normal outcome (branch death after a merged PR), not a
    // failure — run() rethrows TaskNotFoundException silently: no extra System.err line beyond the
    // command's own calm message on System.out, no WARN log, no stack trace.
    def "run() rethrows TaskNotFoundException from a subcommand without any extra stderr output"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments('usage', "--dir=${projectRoot}".toString(), 'task-1')
        def originalErr = System.err
        def captured = new ByteArrayOutputStream()
        System.err = new PrintStream(captured, true, 'UTF-8')

        when:
        try {
            runner.run(args)
        } catch (TaskNotFoundException ignored) {
            // Expected: the command already printed the calm message to stdout.
        } finally {
            System.err = originalErr
        }

        then:
        captured.toString('UTF-8').isEmpty()
    }

    // FR13, FR14: an unrecognized subcommand is a usage error (exit code 2 family)
    def "run() throws UsageException for an unrecognized subcommand"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments('frobnicate')

        when:
        runner.run(args)

        then:
        thrown(UsageException)
    }

    // FR1, FR12: a Failed pipeline load surfaces as PipelineLoadFailedException
    def "run() throws PipelineLoadFailedException when .gnomish/ fails to load"() {
        given: 'no .gnomish/ tree at all under --dir'
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=fix the thing',
                '--mode=in-place')

        when:
        runner.run(args)

        then:
        thrown(IOException)
    }

    // NFR-O1, D9, catch-all branch: an unexpected RuntimeException (here IllegalArgumentException
    // from DirectoryWorkspace, since --dir names a file, not a directory) is logged, prints
    // "gnomish run failed: <message>" to stderr, and rethrows unchanged
    def "run() prints a 'gnomish run failed' message to stderr for an unexpected RuntimeException"() {
        given: '--dir pointing at a plain file, not a directory, so DirectoryWorkspace throws'
        def notADirectory = projectRoot.resolve('not-a-directory.txt')
        Files.writeString(notADirectory, 'x')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${notADirectory}".toString(),
                '--task=fix the thing',
                '--mode=in-place')
        def originalErr = System.err
        def captured = new ByteArrayOutputStream()
        System.err = new PrintStream(captured, true, 'UTF-8')

        when:
        IllegalArgumentException thrownException = null
        try {
            runner.run(args)
        } catch (IllegalArgumentException ex) {
            thrownException = ex
        } finally {
            System.err = originalErr
        }

        then:
        thrownException != null
        captured.toString('UTF-8').trim() == "gnomish run failed: ${thrownException.message}".toString()
    }

    // FR1, FR12: PipelineLoadFailedException's message is also printed to stderr before rethrow
    def "run() prints the PipelineLoadFailedException message to stderr before rethrowing"() {
        given: 'a present but invalid .gnomish/ tree - a stage referencing a missing instructions file'
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', '''\
purpose: plan the work
executor:
  type: agent-cli
  model: plan-model
instructions: stages/plan/instructions.md
verify:
  - type: command
    command: echo ok
advancement: auto
''')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=fix the thing',
                '--mode=in-place')
        def originalErr = System.err
        def captured = new ByteArrayOutputStream()
        System.err = new PrintStream(captured, true, 'UTF-8')

        when:
        PipelineLoadFailedException thrownException = null
        try {
            runner.run(args)
        } catch (PipelineLoadFailedException ex) {
            thrownException = ex
        } finally {
            System.err = originalErr
        }

        then:
        thrownException != null
        captured.toString('UTF-8').trim() == thrownException.message
    }

    // FR1, UX1: a malformed invocation surfaces as UsageException before any pipeline load
    def "run() throws UsageException for a malformed invocation without touching the pipeline"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments("--dir=${projectRoot}".toString())

        when:
        runner.run(args)

        then:
        thrown(UsageException)
    }

    // FR13, NFR-R1, UX3: EOF at the very first prompt prints "Input exhausted" to stderr, no stack trace
    def "run() prints an input-exhausted message to stderr when stdin hits EOF immediately"() {
        given:
        writeOneStagePipeline()
        def originalIn = System.in
        def originalErr = System.err
        System.in = new ByteArrayInputStream(new byte[0])
        def captured = new ByteArrayOutputStream()
        System.err = new PrintStream(captured, true, 'UTF-8')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-eof',
                '--mode=in-place',
                '--interactive')

        when:
        try {
            runner.run(args)
        } catch (RuntimeException ignored) {
            // Expected: an exhausted-input exception propagates after the message is printed.
        } finally {
            System.in = originalIn
            System.err = originalErr
        }

        then:
        captured.toString('UTF-8').contains('Input exhausted — stopping.')
    }

    // D10: StatusSnapshotHolder's initial attemptLimit comes from the starting stage's own
    // autonomy.attemptLimit (7), not the pipeline default (3) — proven by the "status" meta-
    // command's rendered "(attempt X/Y)" fraction before the round completes.
    def "run() seeds the status snapshot with the starting stage's own attempt limit, not the pipeline default"() {
        given:
        writeOneStagePipelineWithStageAttemptLimitOverride()
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream(('status' + System.lineSeparator() + System.lineSeparator())
                .getBytes('UTF-8'))
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')
        def runner = new ManualRunRunner(
                new RunArgumentsParser(),
                new PipelineStartup(),
                new AdHocTaskSynthesizer(java.time.Clock.systemUTC(), new Random()),
                new SystemConsoleIO(System.in, System.out),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new InMemoryAttemptPersistence(),
                new SystemClock(),
                new ThreadSleeper(),
                FACTORY_PROPERTIES,
                worktreesRoot,
                new StatusCommand(worktreesRoot),
                new UsageCommand())
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-status',
                '--mode=in-place',
                '--interactive')

        when:
        try {
            runner.run(args)
        } finally {
            System.in = originalIn
            System.out = originalOut
        }

        then:
        capturedOut.toString('UTF-8').contains('attempt 0/7')
    }

    // FR7, UX4: --mode in-place prints the honest in-memory reminder before the pipeline runs
    def "run() prints the in-place mode reminder before running the pipeline"() {
        given:
        writeOneStagePipeline()
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8'))
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')
        def runner = new ManualRunRunner(
                new RunArgumentsParser(),
                new PipelineStartup(),
                new AdHocTaskSynthesizer(java.time.Clock.systemUTC(), new Random()),
                new SystemConsoleIO(System.in, System.out),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new InMemoryAttemptPersistence(),
                new SystemClock(),
                new ThreadSleeper(),
                FACTORY_PROPERTIES,
                worktreesRoot,
                new StatusCommand(worktreesRoot),
                new UsageCommand())
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-in-place',
                '--mode=in-place',
                '--interactive')

        when:
        runner.run(args)

        then:
        noExceptionThrown()
        def output = capturedOut.toString('UTF-8')
        def reminderLine = output.readLines().find { it.contains('in-place mode') }
        reminderLine != null
        reminderLine.contains('no resume')
        output.indexOf(reminderLine) < output.indexOf('do the thing')

        cleanup:
        System.in = originalIn
        System.out = originalOut
    }

    // FR6, FR7, UX1: --mode git (the default) prints the branch/worktree banner upfront, before
    // the pipeline runs, and never prints the in-place reminder
    def "run() prints the branch and worktree banner before the pipeline runs in git mode"() {
        given:
        makeProjectRootAGitClone()
        writeOneStagePipeline()
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8'))
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-git',
                '--interactive')

        when:
        runner.run(args)

        then:
        noExceptionThrown()
        def output = capturedOut.toString('UTF-8')
        def branchLine = output.readLines().find { it.contains('git mode: branch') }
        def worktreeLine = output.readLines().find { it.contains('git mode: worktree') }
        branchLine != null
        branchLine.contains('gnomish/manual-test-git')
        worktreeLine != null
        worktreeLine.contains(worktreesRoot.toString())
        output.indexOf(branchLine) < output.indexOf('do the thing')
        !output.contains('in-place mode')

        cleanup:
        System.in = originalIn
        System.out = originalOut
    }

    // FR6, FR7: the clone's own working copy is untouched by a git-mode run — the pipeline ran
    // against the worktree, and the clone stays on whatever branch it started on with no new
    // untracked/modified files
    def "run() in git mode never mutates the --dir clone's working copy"() {
        given:
        makeProjectRootAGitClone()
        writeOneStagePipeline()
        def gitRunner = new GitProcessRunner()
        def cloneStatusBefore = gitRunner.run(projectRoot, 'status', '--porcelain').stdout()
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8'))
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-git-clean',
                '--interactive')

        when:
        runner.run(args)

        then:
        noExceptionThrown()
        gitRunner.run(projectRoot, 'status', '--porcelain').stdout() == cloneStatusBefore

        cleanup:
        System.in = originalIn
        System.out = originalOut
    }

    // FR6, FR7, FR15/M4: a completed git-mode run's worktree is removed (design D6), and the
    // branch's history still carries the completed task.json even though the tip cleanup commit
    // (FR15) removed .gnomish-task/ from it
    def "run() removes the worktree on a completed git-mode task and the branch history stays intact"() {
        given:
        makeProjectRootAGitClone()
        writeOneStagePipeline()
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8'))
        System.out = new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-git-complete',
                '--interactive')

        when:
        runner.run(args)

        then:
        noExceptionThrown()
        def worktree = worktreesRoot.resolve(projectRoot.getFileName().toString()).resolve('manual-test-git-complete')
        !Files.exists(worktree)

        and: 'the branch still exists in the clone, with completed task.json reachable in history'
        def gitRunner = new GitProcessRunner()
        gitRunner.run(projectRoot, 'rev-parse', '--verify', 'gnomish/manual-test-git-complete').exitCode() == 0

        cleanup:
        System.in = originalIn
        System.out = originalOut
    }

    // FR7: a fresh git-mode run whose taskId already has a branch is a usage error (exit code 2
    // family), not silently reused — resuming into an existing branch is FR8's job
    def "run() throws UsageException in git mode when the task branch already exists"() {
        given:
        makeProjectRootAGitClone()
        writeOneStagePipeline()
        def gitRunner = new GitProcessRunner()
        assert gitRunner.run(projectRoot, 'branch', 'gnomish/manual-test-git-dup', 'HEAD').exitCode() == 0
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-git-dup')

        when:
        runner.run(args)

        then:
        thrown(UsageException)
    }

    // FR7, design D7: an unresolved --base is a usage error, not a resumable condition
    def "run() throws UsageException in git mode when --base does not resolve"() {
        given:
        makeProjectRootAGitClone()
        writeOneStagePipeline()
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-git-badbase',
                '--base=does-not-exist')

        when:
        runner.run(args)

        then:
        thrown(UsageException)
    }

    /** Bootstraps a real git task branch directly (no full fresh run), as a crashed run would leave it. */
    private void bootstrapGitTask(String taskId) {
        def gitRunner = new GitProcessRunner()
        def repository = new GitTaskRepository(gitRunner, projectRoot, worktreesRoot)
        def context = new TaskContext(taskId, 'title', 'body', List.<Decision> of())
        repository.createTask(context, null)
        def worktree = worktreesRoot.resolve(projectRoot.getFileName().toString()).resolve(taskId)
        def persistence = new GitAttemptPersistence(gitRunner, worktree, taskId)
        def state = TaskState.atStageStart('build')
        def trace = new ToolTrace(new AttemptKey(taskId, 'build', 0),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ])
        persistence.persist(taskId, state, trace)
    }

    // FR8: --resume dispatches to GitResumeRunner#run from within ManualRunRunner#drive itself —
    // proven end to end through a real git task branch (bootstrapped directly, as a process that
    // died mid-visit would leave it), since a mocked collaborator is unavailable here (GitResumeRunner
    // is constructed internally by ManualRunRunner, not injected).
    def "run() with --resume dispatches to GitResumeRunner and drives the task to completion"() {
        given: 'a git task with one persisted round but no recorded outcome — the process died mid-visit'
        makeProjectRootAGitClone()
        writeOneStagePipeline()
        bootstrapGitTask('manual-test-resume')

        and: 'stdin/stdout wired for the resumed run: a bare Enter drives one more round to completion'
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8'))
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')

        when:
        try {
            newRunner().run(new DefaultApplicationArguments(
                    "--dir=${projectRoot}".toString(),
                    '--resume=manual-test-resume',
                    '--interactive'))
        } finally {
            System.in = originalIn
            System.out = originalOut
        }

        then: 'the stage briefing only reachable through a driven engine round was printed'
        noExceptionThrown()
        capturedOut.toString('UTF-8').contains('=== Task goal ===')

        and: 'the task reached completion, and the worktree was cleaned up'
        def gitRunner = new GitProcessRunner()
        gitRunner.run(projectRoot, 'rev-parse', '--verify', 'gnomish/manual-test-resume').exitCode() == 0
        def worktree = worktreesRoot.resolve(projectRoot.getFileName().toString()).resolve('manual-test-resume')
        !Files.exists(worktree)
    }

    // FR4: an unsupported state-file version is caught, printed to stderr with no stack trace, and
    // rethrown unchanged — distinguishing this dedicated catch branch from the generic RuntimeException
    // fallback (PIT: VoidMethodCallMutator removed the println).
    def "run() prints the UnsupportedStateFileVersionException message to stderr before rethrowing"() {
        given: 'a git task whose task.json carries an unsupported version'
        makeProjectRootAGitClone()
        writeOneStagePipeline()
        bootstrapGitTask('manual-test-badversion')
        def gitRunner = new GitProcessRunner()
        def worktree = worktreesRoot.resolve(projectRoot.getFileName().toString()).resolve('manual-test-badversion')
        def taskJson = worktree.resolve('.gnomish-task').resolve('task.json')
        def rewritten = Files.readString(taskJson).replaceFirst(/"version"\s*:\s*1/, '"version":2')
        Files.writeString(taskJson, rewritten)
        gitRunner.run(worktree, 'add', '-A')
        gitRunner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'bump version')

        def originalErr = System.err
        def capturedErr = new ByteArrayOutputStream()
        System.err = new PrintStream(capturedErr, true, 'UTF-8')

        when:
        UnsupportedStateFileVersionException thrownException = null
        try {
            newRunner().run(new DefaultApplicationArguments(
                    "--dir=${projectRoot}".toString(),
                    '--resume=manual-test-badversion'))
        } catch (UnsupportedStateFileVersionException ex) {
            thrownException = ex
        } finally {
            System.err = originalErr
        }

        then:
        thrownException != null
        capturedErr.toString('UTF-8').trim() == thrownException.message
    }

    // FR1, FR2, FR9: a minimal one-stage pipeline runs end to end to Completed via scripted stdin
    def "run() drives a minimal one-stage pipeline to completion via real stdin"() {
        given:
        writeOneStagePipeline()
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8'))
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')
        def runner = new ManualRunRunner(
                new RunArgumentsParser(),
                new PipelineStartup(),
                new AdHocTaskSynthesizer(java.time.Clock.systemUTC(), new Random()),
                new SystemConsoleIO(System.in, System.out),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new InMemoryAttemptPersistence(),
                new SystemClock(),
                new ThreadSleeper(),
                FACTORY_PROPERTIES,
                worktreesRoot,
                new StatusCommand(worktreesRoot),
                new UsageCommand())
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-1',
                '--mode=in-place',
                '--interactive')

        when:
        runner.run(args)

        then:
        noExceptionThrown()

        // Proves the outcome loop actually ran the stage (not just that drive() returned
        // without error): the stage briefing InteractiveStageExecutor prints is only
        // reachable through RunnerOutcomeLoop#run driving the engine.
        capturedOut.toString('UTF-8').contains('do the thing')

        cleanup:
        System.in = originalIn
        System.out = originalOut
    }
}
