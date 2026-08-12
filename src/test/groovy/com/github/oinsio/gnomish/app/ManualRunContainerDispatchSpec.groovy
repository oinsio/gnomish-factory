package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.BindingProperties
import com.github.oinsio.gnomish.SandboxProperties
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.function.BooleanSupplier
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14, G2, D13 of add-sandbox-core (the integration pass): {@code gnomish run}'s drive
 * dispatches to the container runners when the resolved bindings plan a CONTAINER run — the
 * fresh git-mode path to {@link ContainerGitModeRunner}, {@code --resume} to
 * {@link ContainerResumeRunner}. Daemon-free: the D13 prerequisite probe is the runner's
 * package-private test seam, and each dispatched runner is stopped by its own early usage
 * refusal right after its observable start (banner / branch lookup).
 */
class ManualRunContainerDispatchSpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path projectRoot

    @TempDir
    Path worktreesRoot

    @TempDir
    Path homeDir

    private ManualRunRunner newContainerRunner() {
        def runner = new ManualRunRunner(
                new RunArgumentsParser(),
                new PipelineStartup([:]),
                new AdHocTaskSynthesizer(Clock.systemUTC(), new Random()),
                new SystemConsoleIO(System.in, System.out),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new InMemoryAttemptPersistence(),
                new SystemClock(),
                new ThreadSleeper(),
                testProperties(),
                new SandboxProperties('gnomish/img', null, null, null, [], [], false),
                // Container by default (D13): no explicit binding, image configured.
                new BindingProperties(null, [:]),
                worktreesRoot,
                homeDir,
                new StatusCommand(worktreesRoot),
                new UsageCommand(),
                new BoardCommand(Clock.systemUTC(), testProperties(), [:], [:]),
                new DashboardCommand(Clock.systemUTC(), new ThreadSleeper(), homeDir, testProperties(), [:], [:]),
                Clock.systemUTC(),
                [:],
                [:],
                new ServeProperties(0, null, null, null, null, null))
        // The D13 prerequisite probe, scripted reachable — no daemon in unit tests.
        runner.@dockerProbe = { true } as BooleanSupplier
        runner
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

    private void makeProjectRootAGitClone() {
        def runner = new GitProcessRunner()
        assert runner.run(projectRoot, 'init').exitCode() == 0
        Files.writeString(projectRoot.resolve('README.md'), 'seed\n')
        runner.run(projectRoot, 'add', '-A')
        assert runner.run(projectRoot, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        .exitCode() == 0
    }

    // D13, FR14: a fresh git-mode run under container bindings dispatches to
    // ContainerGitModeRunner — its banner prints, and its own duplicate-branch refusal escapes.
    def "run() dispatches a fresh container-bound git run to ContainerGitModeRunner"() {
        given: 'the task branch already exists, so the container runner refuses after its banner'
        makeProjectRootAGitClone()
        writeOneStagePipeline()
        def gitRunner = new GitProcessRunner()
        assert gitRunner.run(projectRoot, 'branch', 'gnomish/ct-dup', 'HEAD').exitCode() == 0
        def originalOut = System.out
        def captured = new ByteArrayOutputStream()
        System.out = new PrintStream(captured, true, 'UTF-8')
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=ct-dup')

        when:
        try {
            newContainerRunner().run(args)
        } finally {
            System.out = originalOut
        }

        then:
        thrown(UsageException)
        captured.toString('UTF-8').contains('container mode: branch gnomish/ct-dup')

        cleanup:
        System.out = originalOut
    }

    // D13, FR6: --resume under container bindings dispatches to ContainerResumeRunner — its
    // branch lookup runs and its own "nothing to resume" refusal escapes.
    def "run() dispatches a container-bound --resume to ContainerResumeRunner"() {
        given:
        makeProjectRootAGitClone()
        writeOneStagePipeline()
        def args = new DefaultApplicationArguments(
                "--dir=${projectRoot}".toString(),
                '--resume=absent-task')

        when:
        newContainerRunner().run(args)

        then:
        def e = thrown(UsageException)
        e.message.contains('no task branch found for "absent-task"')
        e.message.contains('nothing to resume')
    }
}
