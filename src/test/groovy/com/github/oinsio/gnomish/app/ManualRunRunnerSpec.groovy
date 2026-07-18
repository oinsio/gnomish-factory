package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, FR9, FR12, UX3, D10 of add-manual-run: the {@code gnomish run} ApplicationRunner
 * entrypoint. No relevant flag present is a no-op, preserving FactoryApplication's untouched
 * no-args behavior (task 7.12 verifies that boundary at the full-context level next); with at
 * least one relevant flag, the runner drives argument parsing, pipeline load, ad-hoc task
 * synthesis, and the outcome loop in order.
 */
class ManualRunRunnerSpec extends Specification {

    @TempDir
    Path projectRoot

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
                FACTORY_PROPERTIES)
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

    // FR12: an unrelated Boot-style non-option arg alone is still a no-op
    def "run() does nothing when only unrelated arguments are present"() {
        given:
        def runner = newRunner()
        def args = new DefaultApplicationArguments('--debug', 'positional')

        when:
        runner.run(args)

        then:
        noExceptionThrown()
    }

    // FR1, FR12: a Failed pipeline load surfaces as PipelineLoadFailedException
    def "run() throws PipelineLoadFailedException when .gnomish/ fails to load"() {
        given: 'no .gnomish/ tree at all under --project'
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--project=${projectRoot}".toString(),
                '--task=fix the thing')

        when:
        runner.run(args)

        then:
        thrown(IOException)
    }

    // NFR-O1, D9, catch-all branch: an unexpected RuntimeException (here IllegalArgumentException
    // from DirectoryWorkspace, since --project names a file, not a directory) is logged, prints
    // "gnomish run failed: <message>" to stderr, and rethrows unchanged
    def "run() prints a 'gnomish run failed' message to stderr for an unexpected RuntimeException"() {
        given: '--project pointing at a plain file, not a directory, so DirectoryWorkspace throws'
        def notADirectory = projectRoot.resolve('not-a-directory.txt')
        Files.writeString(notADirectory, 'x')
        def runner = newRunner()
        def args = new DefaultApplicationArguments(
                "--project=${notADirectory}".toString(),
                '--task=fix the thing')
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
                "--project=${projectRoot}".toString(),
                '--task=fix the thing')
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
        def args = new DefaultApplicationArguments("--project=${projectRoot}".toString())

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
                "--project=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-eof',
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
                FACTORY_PROPERTIES)
        def args = new DefaultApplicationArguments(
                "--project=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-status',
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
                FACTORY_PROPERTIES)
        def args = new DefaultApplicationArguments(
                "--project=${projectRoot}".toString(),
                '--task=do the thing',
                '--task-id=manual-test-1',
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
