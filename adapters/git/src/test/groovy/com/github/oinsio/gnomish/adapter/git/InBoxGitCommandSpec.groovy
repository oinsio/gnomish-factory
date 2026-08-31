package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.Termination
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR6 of harden-task-branch-contract (design D14): the container medium's one seam mapping a
 * native in-box invocation outcome onto the named termination taxonomy, so no call site has to
 * classify {@code CapturedExec}'s exception shape for itself.
 */
class InBoxGitCommandSpec extends Specification {

    def "FR6: a command that ran to its own exit reports EXITED with its code and output"() {
        given:
        def environment = environmentReturning(new FakeHandle(0, 'committed\n', false))

        when:
        def outcome = new InBoxGitCommand(environment).run('in-box commit', 'true', [])

        then:
        outcome.termination() == Termination.EXITED
        outcome.exitCode() == 0
        outcome.output() == 'committed\n'
        outcome.succeeded()
    }

    def "FR6: a non-zero exit is still EXITED — the command answered, it just said no"() {
        given:
        def environment = environmentReturning(new FakeHandle(3, 'nothing to commit\n', false))

        when:
        def outcome = new InBoxGitCommand(environment).run('in-box commit', 'false', [])

        then:
        outcome.termination() == Termination.EXITED
        outcome.exitCode() == 3
        !outcome.succeeded()
    }

    def "FR6: an interrupted wait comes back named, never as an exception the call site must classify"() {
        given: 'a handle whose supervised wait was cut short by an interrupt'
        def environment = environmentReturning(new FakeHandle(-1, '', true))

        when:
        def outcome = new InBoxGitCommand(environment).run('in-box commit', 'true', [])

        then:
        outcome.termination() == Termination.INTERRUPTED
        !outcome.succeeded()
        outcome.output().contains('interrupted')

        cleanup: 'the flag CapturedExec left set belongs to the shutdown, not to the next spec'
        Thread.interrupted()
    }

    def "FR6: a broken output stream is a defect, not a termination, and propagates unchanged"() {
        given:
        def environment = environmentReturning(new BrokenStreamHandle())

        when:
        new InBoxGitCommand(environment).run('in-box commit', 'true', [])

        then:
        thrown(UncheckedIOException)
    }

    def "FR6: the script's positional arguments follow it into the sh -c argv"() {
        given:
        List<String> seen = []
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> { ExecCommand command ->
                seen = command.command()
                new FakeHandle(0, '', false)
            }
        }

        when:
        new InBoxGitCommand(environment).run('in-box commit', 'script-body', ['gnomish', 'a', 'b'])

        then:
        seen == [
            'sh',
            '-c',
            'script-body',
            'gnomish',
            'a',
            'b'
        ]
    }

    private TaskExecutionEnvironment environmentReturning(ExecHandle handle) {
        Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> handle
        }
    }

    private static class FakeHandle implements ExecHandle {

        private final int exitCode
        private final String output
        private final boolean interrupt

        FakeHandle(int exitCode, String output, boolean interrupt) {
            this.exitCode = exitCode
            this.output = output
            this.interrupt = interrupt
        }

        @Override
        InputStream output() {
            new ByteArrayInputStream(output.bytes)
        }

        @Override
        Instant startedAt() {
            Instant.EPOCH
        }

        @Override
        ExecHandle.Wait waitForExitOrTimeout(Duration timeout, Clock clock) {
            new ExecHandle.Wait.Exited(Duration.ZERO)
        }

        @Override
        int waitForExit() {
            if (interrupt) {
                Thread.currentThread().interrupt()
            }
            exitCode
        }
    }

    private static class BrokenStreamHandle extends FakeHandle {

        BrokenStreamHandle() {
            super(0, '', false)
        }

        @Override
        InputStream output() {
            new InputStream() {
                        @Override
                        int read() {
                            throw new IOException('pipe broke')
                        }
                    }
        }
    }
}
