package com.github.oinsio.gnomish.adapter.agent.program

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * {@link ProgramExecutor} against {@link FakeProcess}, handed to the host environment through its
 * {@link ProcessLaunch} (design D18 of define-executor-contract; testing.md, "A scheduling order is
 * never a premise"): the spec writes the files the program would, from inside the launch, before the
 * fake is handed back, and gates the exit itself, so no feature rests on which of two threads runs
 * first. The silence bound is reached on virtual time; the overall bound of a fake that never
 * exits is a short real wait whose expiry is the only way the run can end.
 */
@Timeout(60)
class ProgramExecutorProcessSpec extends Specification {

    static final String VALID = StandIn.data('executor-result#completed')

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    LogCaptureSupport logs

    def cleanup() {
        logs?.detach()
    }

    /** Runs one start whose process is {@code process}, after {@code files} wrote what the program would. */
    private ExecutorResult start(FakeProcess process, Closure files, List<Capability> capabilities = [],
            ProgramBounds bounds = ProgramBounds.DEFAULTS) {
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            files(Path.of(builder.environment()['GNOMISH_RESULT_FILE']), builder.redirectError().file().toPath())
            process
        } as ProcessLaunch
        def environment = new HostTaskExecutionEnvironment(workspace, new VirtualClock(), ChildEnvAllowlist.none(), launch)
        environment.materialize('gnomish/TASK-7', null)
        try {
            new ProgramExecutor(ProgramFixtures.declaration(['acme-program']), ProgramFixtures.describe(capabilities),
            [], environment, ProgramFixtures.equipment(stderrParent, bounds, VirtualTimeEquipment.create()))
            .start(ProgramFixtures.request())
        } finally {
            environment.dispose()
        }
    }

    // FR6: a result that is not JSON is the run's failed/infrastructure naming the parse failure, never a pass
    def "FR6: a malformed result file is failed/infrastructure naming it"() {
        when:
        def result = start(new FakeProcess(), { Path result, Path stderr ->
            Files.writeString(result, '{"status":')
        })

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('wrote a malformed result')
    }

    // Scenario: executor/result-channel / Verbose executor does not deadlock the factory
    // FR5: stderr far past a pipe buffer goes to a file; the result is read and a failure's quote is capped
    def "FR5: 200 KiB of stderr neither blocks the run nor enters the result, and a failure quotes only its capped tail"() {
        given:
        def noise = ('x' * 1023 + '\n') * 200 + 'executor: the last line\n'

        when:
        def completed = start(new FakeProcess(), { Path result, Path stderr ->
            Files.writeString(stderr, noise)
            Files.writeString(result, VALID)
        })
        def failed = start(new FakeProcess(), { Path result, Path stderr ->
            Files.writeString(stderr, noise)
        })

        then: 'the run completed from its result file'
        completed.status() == Status.COMPLETED

        and: 'the failure quotes the end of the stderr, capped'
        failed.reason().contains('executor: the last line')
        failed.reason().length() <RoundScratchDirectory.STDERR_TAIL_BYTES
        Files.list(stderrParent).count() == 0
    }

    // Scenario: executor/result-channel / Stray print does not corrupt the result
    // FR5: free text on stdout is a capped DEBUG line; the result comes from the file
    def "FR5: free text on stdout beside a valid result file is a log line and the result is the file's"() {
        given:
        logs = LogCaptureSupport.attach(ProgramInvocation, Level.DEBUG)
        def process = new FakeProcess(0, 'Hello! {"status":"failed"} is not my result\n' * 500) {}

        when:
        def result = start(process, { Path result, Path stderr ->
            Files.writeString(result, VALID)
        })

        then:
        result.status() == Status.COMPLETED
        logs.list.size() == 1
        logs.list[0].level == Level.DEBUG
        logs.list[0].formattedMessage.contains('is not my result')
    }

    // Scenario: executor/wire-contract / Silence bound expires
    // NFR-R2: a streams_progress run that says nothing past the silence bound is killed and failed/infrastructure naming it
    def "NFR-R2: a progress-streaming run silent past its silence bound is killed, failed/infrastructure naming the bound"() {
        given: 'a live process that writes nothing and never exits on its own'
        def process = FakeProcess.running()
        def bounds = new ProgramBounds(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofHours(1))

        when:
        def result = start(process, { Path result, Path stderr -> }, [Capability.STREAMS_PROGRESS], bounds)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('the silence bound of PT1S expired and its process tree was killed')

        and: 'the tree was killed: the fake took the polite signal'
        !process.alive
        process.exitValue() == 143
    }

    // NFR-R2: a run that never exits is killed at the overall bound, failed/infrastructure naming it
    def "NFR-R2: a run past its overall bound is killed, failed/infrastructure naming the bound"() {
        given:
        def process = FakeProcess.running()
        def bounds = new ProgramBounds(Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMillis(50))

        when:
        def result = start(process, { Path result, Path stderr -> }, [], bounds)

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('the overall bound of PT0.05S expired')
        !process.alive
    }

    // FR5: a progress-streaming run that exits is read from its result file once its stream ends
    def "FR5: a progress-streaming run that exited is read from its result file"() {
        when:
        def result = start(new FakeProcess(0, '{"progress":1}\n') {}, { Path result, Path stderr ->
            Files.writeString(result, VALID)
        }, [Capability.STREAMS_PROGRESS])

        then:
        result.status() == Status.COMPLETED
    }
}
