package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.io.InterruptedIOException
import java.io.UncheckedIOException
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR6, FR12 of bound-subprocess-commands: a run that never produced an exit code is classified
 * from its termination alone, so the findings channel is not touched. A timed-out check's findings
 * file is half-written by construction; an interrupted check's read runs with the interrupt flag
 * still set, and a container environment answers that read with an {@code InterruptedIOException}
 * rather than bytes — so a read taken before classification turns the infrastructure verdict into
 * a thrown exception.
 */
class ShellCommandCheckRunnerFindingsReadSpec extends Specification implements ShellCommandCheckRunnerTestSupport {

    @TempDir
    Path tempDir

    private final List<RecordingEnvironment> environments = []

    private ShellCommandCheckRunner runner(Duration checkTimeout) {
        new ShellCommandCheckRunner()
                .withEnvironments(recordingSource())
                .withCheckTimeout(checkTimeout)
    }

    /**
     * A check environment whose {@code readFile} records every path and refuses an interrupted
     * read the way {@code ContainerFileChannel} does — its in-box read is a {@code docker exec}
     * supervised by the shared {@code ProcessSupervisor}, whose wait throws immediately when the
     * calling thread's interrupt flag is already set.
     */
    private CheckEnvironmentSource recordingSource() {
        def host = new HostCheckEnvironmentSource(new SystemClock(), ChildEnvAllowlist.none())
        return { check, workspace ->
            def acquired = host.acquire(check, workspace)
            def environment = new RecordingEnvironment(acquired.environment())
            environments << environment
            new CheckEnvironmentSource.Acquired() {

                        @Override
                        TaskExecutionEnvironment environment() {
                            environment
                        }

                        @Override
                        void close() {
                            acquired.close()
                        }
                    }
        } as CheckEnvironmentSource
    }

    private static class RecordingEnvironment implements TaskExecutionEnvironment {

        @Delegate
        private final TaskExecutionEnvironment delegate

        final List<String> reads = []

        RecordingEnvironment(TaskExecutionEnvironment delegate) {
            this.delegate = delegate
        }

        @Override
        Optional<byte[]> readFile(String path, long sizeCap) {
            reads << path
            if (Thread.currentThread().isInterrupted()) {
                throw new UncheckedIOException(
                new InterruptedIOException("in-box read of $path did not complete: the wait was interrupted"))
            }
            return delegate.readFile(path, sizeCap)
        }
    }

    @Timeout(30)
    def "FR6: an interrupted check stays CannotVerify when the environment refuses an interrupted read"() {
        given: 'the calling thread is already interrupted when the wait starts'
        def check = command('while true; do sleep 1; done')
        def runner = runner(Duration.ofSeconds(30))

        when:
        def verdict = null
        def failure = null
        def worker = Thread.start {
            Thread.currentThread().interrupt()
            try {
                verdict = runner.run(check, workspace())
            } catch (Throwable t) {
                failure = t
            }
        }
        worker.join()

        then: 'the shutdown resolves as infrastructure, not as a thrown read failure'
        failure == null
        verdict instanceof Verdict.CannotVerify
        (verdict as Verdict.CannotVerify).reason().toLowerCase().contains('interrupted')

        and: 'no findings read was attempted at all'
        environments.every { it.reads.isEmpty() }
    }

    @Timeout(30)
    def "FR12: a timed-out check does not read the findings file it left half-written"() {
        given: 'a command that writes to the findings channel and then never exits'
        def check = command('echo hung-output; echo not-json > "$GNOMISH_FINDINGS_FILE"; while true; do sleep 1; done')

        when:
        def verdict = runner(Duration.ofMillis(300)).run(check, workspace())

        then:
        verdict instanceof Verdict.Fail
        (verdict as Verdict.Fail).findings()[0].message().contains('timed out')

        and:
        environments.every { it.reads.isEmpty() }
    }

    def "NFR-R2: a check that chose its own exit code still reads the findings channel"() {
        when:
        def verdict = runner(Duration.ofSeconds(30)).run(command('exit 3'), workspace())

        then:
        verdict instanceof Verdict.Fail
        environments.any { !it.reads.isEmpty() }
    }
}
