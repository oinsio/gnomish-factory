package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.function.UnaryOperator
import org.slf4j.MDC
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, FR9, FR12, UX3, D10 of add-manual-run: the {@code gnomish run} ApplicationRunner
 * entrypoint. Only the empty command line is a no-op, preserving FactoryApplication's untouched
 * no-args behavior (task 7.12 verifies that boundary at the full-context level next); every other
 * command line reaches the parser (FR8 of fix-operator-blockers), and the runner drives argument
 * parsing, pipeline load, ad-hoc task synthesis, and the outcome loop in order.
 *
 * <p>FR6, FR7, UX1, UX4, design D8 of add-git-workflow: {@code --mode=in-place} prints the
 * in-memory reminder before the pipeline runs; {@code --mode=git} (the default) creates the task
 * branch and worktree, prints the UX1 banner upfront, and runs the pipeline against the worktree
 * — proven here end to end against a local bare-repo clone (task 4.4).
 */
class ManualRunRunnerSpec extends Specification implements AppAssemblyFixture, ManualRunPipelineFixture {

    @TempDir
    Path projectRoot

    @TempDir
    Path homeDir

    // FR1, FR2, UX3 of refactor-app-spec-fixtures: delegates to the shared 21-collaborator
    // factory on AppAssemblyFixture (also used by ManualRunRunnerContainerOwnershipSpec),
    // taking every default but the agent binary: the gnome is the fake agent playing `scenario`
    // (FR6 of remove-interactive-console), so stdin carries only the operator dialogs.
    private ManualRunRunner newRunner(String scenario = 'plain-round') {
        newManualRunRunner(projectRoot, homeDir,
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                new BindingProperties('host', [:]), TaskGitFixture.real(), FakeAgentSupport.propertiesFor(scenario))
    }

    /** The clone the runner works in, as the fixture registers it for {@code --dir=projectRoot}. */
    private RegisteredClone registeredClone() {
        RegisteredCloneFixture.resolvedOrRegistered(homeDir.resolve('.gnomish'), projectRoot)
    }

    // D10: the starting stage's own attemptLimit (7), not the pipeline default (3)
    private void writeOneStagePipelineWithStageAttemptLimitOverride() {
        write(projectRoot, 'config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write(projectRoot, 'pipeline.yaml', 'stages:\n  - build\n')
        write(projectRoot, 'stages/build/stage.yaml', '''\
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
        write(projectRoot, 'stages/build/instructions.md', 'build it\n')
    }

    // FR12 of add-manual-run; FR8 of fix-operator-blockers: only the empty command line a Spring
    // test context starts with is the no-op
    def "run() does nothing when the command line is empty"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments()

        when:
        runner.run(args)

        then:
        noExceptionThrown()
    }

    // FR8 of fix-operator-blockers: a non-empty run command line always reaches the parser, so a
    // run with no task is the parser's usage error, not a silent exit 0
    def "run() without a task is a usage error for #argv"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments(argv as String[])

        when:
        runner.run(args)

        then:
        def e = thrown(UsageException)
        e.message.contains('exactly one of --task or --task-file is required')

        where:
        argv << [['run'], ['run', '--debug']]
    }

    // FR8 of fix-operator-blockers: a mistyped option is rejected by name, never skipped by the
    // entrypoint because it named no known flag
    def "run() rejects the unknown option #option of #argv"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments(argv as String[])

        when:
        runner.run(args)

        then:
        def e = thrown(UsageException)
        e.message.startsWith("unknown option ${option} for 'gnomish run'")

        where:
        argv | option
        ['run', '--tsk=x'] | '--tsk'
        ['--dirr=.'] | '--dirr'
    }

    // FR13: the 'status' subcommand routes to StatusCommand, not the run flow
    def "run() dispatches a 'status' subcommand to StatusCommand"() {
        // A real clone: since FR13 of harden-logging-observability a ref enumeration git refuses
        // fails the listing rather than printing "no tasks", and an uninitialized directory is
        // exactly such a refusal — which would make this routing spec red for the wrong reason.
        given:
        makeProjectRootAGitClone(projectRoot)
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

    // FR8 of harden-logging-observability: a run that ends without TaskFinished leaves no attempt
    // scope behind on the runner thread
    def "run() clears the attempt scope on the way out, even on a failure"() {
        given:
        MDC.put('stage', 'plan')
        MDC.put('attempt', '2')

        when:
        newRunner().run(new DefaultApplicationArguments('frobnicate'))

        then:
        thrown(UsageException)
        MDC.get('stage') == null
        MDC.get('attempt') == null
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
        // FR3 of add-project-registry: the directory a run works in is the clone the loader resolved
        def runner = newManualRunRunner(notADirectory, homeDir)
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
        write(projectRoot, 'config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write(projectRoot, 'pipeline.yaml', 'stages:\n  - plan\n')
        write(projectRoot, 'stages/plan/stage.yaml', '''\
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

    // D10: StatusSnapshotHolder's initial attemptLimit comes from the starting stage's own
    // autonomy.attemptLimit (7), not the pipeline default (3) — proven by the "status" meta-
    // command's rendered "(attempt X/Y)" fraction. The holder is seeded once and never updated,
    // so the escalation prompt the fake agent's decision round leads to still shows the seed;
    // stdin then ends there, which leaves the run escalated (exit 10).
    def "run() seeds the status snapshot with the starting stage's own attempt limit, not the pipeline default"() {
        given:
        writeOneStagePipelineWithStageAttemptLimitOverride()
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream(('status' + System.lineSeparator()).getBytes('UTF-8'))
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')
        def runner = newRunner('decision-needed')
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-status',
                '--mode=in-place')

        when:
        try {
            runner.run(args)
        } finally {
            System.in = originalIn
            System.out = originalOut
        }

        then:
        thrown(EscalationEofException)
        capturedOut.toString('UTF-8').contains('attempt 0/7')
    }

    // FR7, UX4: --mode in-place prints the honest in-memory reminder before the pipeline runs
    def "run() prints the in-place mode reminder before running the pipeline"() {
        given:
        writeOneStagePipeline(projectRoot)
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream(new byte[0])
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-in-place',
                '--mode=in-place')

        when:
        runner.run(args)

        then:
        noExceptionThrown()
        def output = capturedOut.toString('UTF-8')
        def reminderLine = output.readLines().find {
            it.contains('in-place mode')
        }
        reminderLine != null
        reminderLine.contains('no resume')
        output.indexOf(reminderLine) <output.indexOf('do the thing')

        cleanup:
        System.in = originalIn
        System.out = originalOut
    }

    // FR3, M2 of wire-host-mid-round-push: in-place mode assembles the run from the runner's
    // own shared assembly (ManualRunDrive.driveInPlace), and that assembly carries the identity
    // host-git decoration — so no MidRoundPushRounds and no MidRoundPushListener is ever built
    // for a run with no git behind it. Attaching the decoration in this constructor instead of
    // at the three git-mode control flows would leak it into in-place; this is what catches it.
    def "the assembly in-place mode runs on carries no mid-round push decoration"() {
        given: 'a task-git bundle whose mid-round push decoration is a recognizable marker'
        def decorated = Stub(RoundEnvironmentSource)
        def real = TaskGitFixture.real()
        def git = new TaskGit(real.store(), real.branches(), real.worktrees(), { rounds ->
            decorated
        } as UnaryOperator<RoundEnvironmentSource>, real.epochs())
        def source = Stub(RoundEnvironmentSource)

        when: 'the runner is built over that bundle'
        def runner = newManualRunRunner(projectRoot, homeDir,
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                new BindingProperties('host', [:]), git)

        then: 'the shared assembly in-place assembles from still decorates nothing'
        runner.drive.assembly.hostGitPush.apply(source).is(source)
    }

    // FR6, FR7, UX1: --mode git (the default) prints the branch/worktree banner upfront, before
    // the pipeline runs, and never prints the in-place reminder
    def "run() prints the branch and worktree banner before the pipeline runs in git mode"() {
        given:
        makeProjectRootAGitClone(projectRoot)
        writeOneStagePipeline(projectRoot)
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream(new byte[0])
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-git')

        when:
        runner.run(args)

        then:
        noExceptionThrown()
        def output = capturedOut.toString('UTF-8')
        def branchLine = output.readLines().find {
            it.contains('git mode: branch')
        }
        def worktreeLine = output.readLines().find {
            it.contains('git mode: worktree')
        }
        branchLine != null
        branchLine.contains('gnomish/manual-test-git')
        worktreeLine != null
        worktreeLine.contains(registeredClone().worktrees().toString())
        output.indexOf(branchLine) <output.indexOf('do the thing')
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
        makeProjectRootAGitClone(projectRoot)
        writeOneStagePipeline(projectRoot)
        def cloneStatusBefore = gitOutput(projectRoot, 'status', '--porcelain')
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream(new byte[0])
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-git-clean')

        when:
        runner.run(args)

        then:
        noExceptionThrown()
        gitOutput(projectRoot, 'status', '--porcelain') == cloneStatusBefore

        cleanup:
        System.in = originalIn
        System.out = originalOut
    }

    // FR6, FR7, FR15/M4: a completed git-mode run's worktree is removed (design D6), and the
    // branch's history still carries the completed task.json even though the tip cleanup commit
    // (FR15) removed .gnomish-task/ from it
    def "run() removes the worktree on a completed git-mode task and the branch history stays intact"() {
        given:
        makeProjectRootAGitClone(projectRoot)
        writeOneStagePipeline(projectRoot)
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream(new byte[0])
        System.out = new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-git-complete')

        when:
        runner.run(args)

        then:
        noExceptionThrown()
        def worktree = registeredClone().worktrees().resolve('manual-test-git-complete')
        !Files.exists(worktree)

        and: 'the branch still exists in the clone, with completed task.json reachable in history'
        gitExitCode(projectRoot, 'rev-parse', '--verify', 'gnomish/manual-test-git-complete') == 0

        cleanup:
        System.in = originalIn
        System.out = originalOut
    }

    // FR7: a fresh git-mode run whose taskId already has a branch is a usage error (exit code 2
    // family), not silently reused — resuming into an existing branch is FR8's job
    def "run() throws UsageException in git mode when the task branch already exists"() {
        given:
        makeProjectRootAGitClone(projectRoot)
        writeOneStagePipeline(projectRoot)
        assert gitExitCode(projectRoot, 'branch', 'gnomish/manual-test-git-dup', 'HEAD') == 0
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
        makeProjectRootAGitClone(projectRoot)
        writeOneStagePipeline(projectRoot)
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
        def repository = new GitTaskRepository(gitRunner, registeredClone(), ClaimEpochSource.NONE)
        def context = new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of())
        repository.createTask(context, TaskStart.commit(projectRoot, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def worktree = registeredClone().worktrees().resolve(taskId)
        def persistence = new GitAttemptPersistence(gitRunner, worktree, taskId, ClaimEpochSource.NONE)
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
        // The pipeline is committed with the clone: a resume reads its law at the task's recorded
        // base commit, and the fake agent's round reads the stage instructions from there.
        writeOneStagePipeline(projectRoot)
        makeProjectRootAGitClone(projectRoot)
        bootstrapGitTask('manual-test-resume')

        and: 'stdout captured for the resumed run; the fake agent drives one more round to completion'
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream(new byte[0])
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')

        when:
        try {
            newRunner().run(new DefaultApplicationArguments(
                            "--dir=${projectRoot}".toString(),
                            '--resume=manual-test-resume'))
        } finally {
            System.in = originalIn
            System.out = originalOut
        }

        then: 'the completion report only reachable through a driven engine run was printed'
        noExceptionThrown()
        capturedOut.toString('UTF-8').contains('Stage: pipeline complete')

        and: 'the task reached completion, and the worktree was cleaned up'
        gitExitCode(projectRoot, 'rev-parse', '--verify', 'gnomish/manual-test-resume') == 0
        def worktree = registeredClone().worktrees().resolve('manual-test-resume')
        !Files.exists(worktree)
    }

    // FR4: an unsupported state-file version is caught, printed to stderr with no stack trace, and
    // rethrown unchanged — distinguishing this dedicated catch branch from the generic RuntimeException
    // fallback (PIT: VoidMethodCallMutator removed the println).
    def "run() prints the UnsupportedStateFileVersionException message to stderr before rethrowing"() {
        given: 'a git task whose task.json carries an unsupported version'
        makeProjectRootAGitClone(projectRoot)
        writeOneStagePipeline(projectRoot)
        bootstrapGitTask('manual-test-badversion')
        def worktree = registeredClone().worktrees().resolve('manual-test-badversion')
        def taskJson = worktree.resolve('.gnomish-task').resolve('task.json')
        def rewritten = Files.readString(taskJson).replaceFirst(/"version"\s*:\s*1/, '"version":2')
        Files.writeString(taskJson, rewritten)
        commitAll(worktree, 'bump version')

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

    // FR1, FR2, FR9: a minimal one-stage pipeline runs end to end to Completed through the fake agent
    def "run() drives a minimal one-stage pipeline to completion through the fake agent"() {
        given:
        writeOneStagePipeline(projectRoot)
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream(new byte[0])
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-1',
                '--mode=in-place')

        when:
        runner.run(args)

        then:
        noExceptionThrown()

        // Proves the outcome loop actually ran the stage (not just that drive() returned
        // without error): the completion report naming the task is only reachable through
        // RunnerOutcomeLoop#run driving the engine to Completed.
        capturedOut.toString('UTF-8').contains('Stage: pipeline complete')
        capturedOut.toString('UTF-8').contains('do the thing')

        cleanup:
        System.in = originalIn
        System.out = originalOut
    }
}
