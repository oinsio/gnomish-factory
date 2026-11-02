package com.github.oinsio.gnomish.sandbox

import static com.github.oinsio.gnomish.sandbox.CaptureThread.onItsOwnThread

import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import spock.lang.Specification

/**
 * Design D9 of define-executor-contract (FR4, FR5, FR11, NFR-R2): {@link BoundedCapture} is the one
 * sequence of a short bounded read of a process — a capped drain, the handle's bounded wait, the join
 * with the handle's own post-exit bound — and reports the wait as it ended and whether the output may
 * be a prefix, judging nothing else. The output is minted at capture, in the subprocess family.
 */
class BoundedCaptureSpec extends Specification {

    private static final InstantSource CLOCK = InstantSource.fixed(Instant.EPOCH)

    def "a command that exits within the bound is captured whole, its wait and bounds the handle's own"() {
        given:
        def exited = new ExecHandle.Wait.Exited(Duration.ofMillis(7))
        def handle = new CannedExecHandle(process: CannedExecHandle.exited(0, 'the whole answer'), boundedWait: exited)
        BoundedCapture capture = null

        when:
        def failure = onItsOwnThread {
            capture = BoundedCapture.of(handle, 'spec-output', 1024, Duration.ofSeconds(3), CLOCK)
        }

        then: 'the wait is reported as it ended, the output is whole, and the drain was joined with the handle\'s bound'
        failure == null
        capture == new BoundedCapture(exited, UntrustedText.subprocess('the whole answer'), false)
        handle.boundedTimeout == Duration.ofSeconds(3)
        handle.boundReads == 1
    }

    // logging.md, "A new capture source mints at the point of capture": the accessor is the carrier.
    def "the captured output is minted at capture as a subprocess carrier"() {
        given:
        def handle = new CannedExecHandle(process: CannedExecHandle.exited(0, 'said'),
        boundedWait: new ExecHandle.Wait.Exited(Duration.ZERO))
        BoundedCapture capture = null

        when:
        def failure = onItsOwnThread {
            capture = BoundedCapture.of(handle, 'spec-output', 1024, Duration.ofSeconds(3), CLOCK)
        }

        then:
        failure == null
        capture.output().provenance() == Provenance.SUBPROCESS
        capture.output().raw() == 'said'
    }

    def "output over the cap is kept as a prefix and reported incomplete"() {
        given:
        def handle = new CannedExecHandle(process: CannedExecHandle.exited(0, 'abcdefgh'),
        boundedWait: new ExecHandle.Wait.Exited(Duration.ZERO))
        BoundedCapture capture = null

        when:
        def failure = onItsOwnThread {
            capture = BoundedCapture.of(handle, 'spec-output', 4, Duration.ofSeconds(3), CLOCK)
        }

        then:
        failure == null
        capture.output() == UntrustedText.subprocess('abcd')
        capture.incomplete()
    }

    def "output a descendant still writes at the post-exit bound is cut, kept as a prefix and reported incomplete"() {
        given:
        def handle = new CannedExecHandle(process: CannedExecHandle.writingAfterExit(0, 'head of the'),
        bound: Duration.ofMillis(100), boundedWait: new ExecHandle.Wait.Exited(Duration.ZERO))
        BoundedCapture capture = null

        when:
        def failure = onItsOwnThread {
            capture = BoundedCapture.of(handle, 'spec-output', 1024 * 1024, Duration.ofSeconds(3), CLOCK)
        }

        then: 'the join stopped the drain at the bound; nothing past the cap was lost, yet the capture is not whole'
        failure == null
        capture.output().raw().startsWith('head of the')
        capture.incomplete()
        handle.stopped()
    }

    def "a wait that timed out is reported as such, with what the command wrote before the kill"() {
        given:
        def handle = new CannedExecHandle(process: CannedExecHandle.exited(137, 'partial'),
        boundedWait: new ExecHandle.Wait.TimedOut())
        BoundedCapture capture = null

        when:
        def failure = onItsOwnThread {
            capture = BoundedCapture.of(handle, 'spec-output', 1024, Duration.ofMillis(50), CLOCK)
        }

        then:
        failure == null
        capture == new BoundedCapture(new ExecHandle.Wait.TimedOut(), UntrustedText.subprocess('partial'), false)
    }
}
