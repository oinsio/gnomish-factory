package com.github.oinsio.gnomish.subprocess

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR16, FR17, design D15 of fix-terminal-receipt-convergence: the drains of one process are joined
 * under one shared post-exit bound, and when one is still open at its end every stream is closed and
 * the capture is reported cut. Driven over in-process streams that block until they are closed — a
 * held pipe whose holder this spec controls — so no scheduling order is a premise. Each drain then
 * answers whether it was the one cut, so a reader can name the stream in its refusal (FR19).
 */
class DrainJoinAllSpec extends Specification {

    private static final Duration BOUND = Duration.ofMillis(400)

    def "FR16: two held drains cost the bound once, then both streams are closed and the capture is cut"() {
        given:
        HeldStream out = new HeldStream('out')
        HeldStream err = new HeldStream('err')
        Drain stdout = Drain.start(out, 'join-all-spec-out')
        Drain stderr = Drain.start(err, 'join-all-spec-err')
        out.awaitSpoken()
        err.awaitSpoken()

        when:
        long startedAt = System.nanoTime()
        boolean cut = Drain.joinAll(BOUND, stdout, stderr)
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'one shared bound, not one per drain'
        cut
        stdout.cut()
        stderr.cut()
        elapsed >= BOUND
        elapsed <BOUND + Duration.ofMillis(300)

        and: 'both streams were closed, and what each drain read before is kept'
        out.closed.await(5, TimeUnit.SECONDS)
        err.closed.await(5, TimeUnit.SECONDS)
        stdout.captured() == 'out'
        stderr.captured() == 'err'
    }

    def "FR17: one held drain among ended ones closes every stream and reports the cut (held: #heldName)"() {
        given:
        HeldStream held = new HeldStream('held')
        HeldStream ended = new HeldStream('ended')
        ended.close()
        Drain heldDrain = Drain.start(held, 'join-all-spec-held')
        Drain endedDrain = Drain.start(ended, 'join-all-spec-ended')
        held.awaitSpoken()
        List<Drain> drains = heldFirst ? [heldDrain, endedDrain] : [endedDrain, heldDrain]

        when:
        boolean cut = Drain.joinAll(BOUND, drains as Drain[])

        then:
        cut
        held.closed.await(5, TimeUnit.SECONDS)
        ended.closeCount == 2

        and: 'each drain says whether it was the one cut, so a reader can name the stream'
        heldDrain.cut()
        !endedDrain.cut()

        where:
        heldFirst << [true, false]
        heldName = heldFirst ? 'first' : 'second'
    }

    def "NFR-P1: drains that end on their own are not cut, and no stream is closed by the join"() {
        given:
        HeldStream out = new HeldStream('out')
        HeldStream err = new HeldStream('err')
        out.close()
        err.close()
        Drain stdout = Drain.start(out, 'join-all-spec-out')
        Drain stderr = Drain.start(err, 'join-all-spec-err')

        when:
        boolean cut = Drain.joinAll(BOUND, stdout, stderr)

        then:
        !cut
        !stdout.cut()
        !stderr.cut()
        out.closeCount == 1
        err.closeCount == 1
    }

    /** Speaks its text once, then blocks until closed and reports end-of-file. */
    private static final class HeldStream extends InputStream {

        final CountDownLatch closed = new CountDownLatch(1)
        private final CountDownLatch spoken = new CountDownLatch(1)
        private final byte[] head
        private boolean headDelivered
        volatile int closeCount

        HeldStream(String text) {
            head = text.bytes
        }

        void awaitSpoken() {
            assert spoken.await(10, TimeUnit.SECONDS)
        }

        @Override
        int read(byte[] target, int offset, int length) {
            if (!headDelivered) {
                headDelivered = true
                System.arraycopy(head, 0, target, offset, head.length)
                return head.length
            }
            spoken.countDown()
            closed.await()
            return -1
        }

        @Override
        int read() {
            spoken.countDown()
            closed.await()
            return -1
        }

        @Override
        void close() {
            closeCount++
            closed.countDown()
        }
    }
}
