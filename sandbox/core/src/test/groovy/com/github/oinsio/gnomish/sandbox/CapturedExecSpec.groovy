package com.github.oinsio.gnomish.sandbox

import static com.github.oinsio.gnomish.sandbox.CaptureThread.onItsOwnThread

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.io.InterruptedIOException
import java.io.UncheckedIOException
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR2, FR11 of bound-subprocess-commands: {@link CapturedExec} is the one
 * correct capture shape for callers that exec through {@link ExecHandle} and
 * want the whole output plus the exit code — output drained on a virtual thread
 * concurrently with the supervised wait, never read to EOF on the calling
 * thread ahead of it, and an interrupted wait reported by name instead of as
 * the killed process's exit code.
 */
class CapturedExecSpec extends Specification {

    def cleanup() {
        Thread.interrupted() // never leak an interrupt flag into the next feature
    }

    // FR2: the ordinary capture — exit code and complete output of an exited command
    def "captures the exit code and the whole output of an exited command"() {
        given:
        def handle = new CannedExecHandle(process: CannedExecHandle.exited(3, 'all of it'))

        when:
        def captured = CapturedExec.of(handle, 'in-box probe')

        then:
        captured.exitCode() == 3
        captured.output().forParsing() == 'all of it'
    }

    // FR2: the join happens after the wait — output still arriving at exit time is not torn off
    def "output still in flight when the wait resolves is captured whole"() {
        given: 'bytes the child wrote before its exit, first queried only after the wait has returned'
        def handle = new CannedExecHandle(process: CannedExecHandle.exited(0, 'late bytes'))
        handle.process.stdoutPipe().onQuery {
            assert handle.exited.await(10, TimeUnit.SECONDS)
        }

        when:
        def captured = CapturedExec.of(handle, 'in-box probe')

        then:
        captured.output().forParsing() == 'late bytes'
    }

    // FR11: a wait cut short by an interrupt is named, and the stalled pipe cannot hold the caller
    def "an interrupted wait over a stalled pipe is reported by name, not as an exit code"() {
        given: 'a hung in-box command: it never exits and writes nothing, the wait ends only by interrupt'
        def handle = new CannedExecHandle(process: FakeProcess.running(), exit: 143, interruptsWait: true)

        when:
        def failure = onItsOwnThread {
            CapturedExec.of(handle, 'in-box salvage commit')
        }

        then:
        failure instanceof UncheckedIOException
        failure.cause instanceof InterruptedIOException
        failure.message.contains('in-box salvage commit')
    }

    // FR11: the flag the supervised wait restored survives the capture for callers up the stack
    def "the restored interrupt flag survives the capture"() {
        given:
        def handle = new CannedExecHandle(process: CannedExecHandle.exited(130, ''), exit: 130, interruptsWait: true)

        when:
        CapturedExec.of(handle, 'in-box probe')

        then:
        thrown(UncheckedIOException)
        Thread.interrupted() // asserts the flag was still set (and clears it)
    }

    // A broken pipe keeps the readFully contract every migrated call site relied on
    def "a pipe that breaks mid-read surfaces as an UncheckedIOException naming the command"() {
        given:
        def handle = new CannedExecHandle(process: CannedExecHandle.exited(0, 'par'))
        def pipe = handle.process.stdoutPipe()
        pipe.onQuery {
            pipe.onQuery {
                pipe.source().close()
            }
        } // the second query finds the pipe broken

        when:
        CapturedExec.of(handle, 'in-box state commit')

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains('in-box state commit')
    }
}
