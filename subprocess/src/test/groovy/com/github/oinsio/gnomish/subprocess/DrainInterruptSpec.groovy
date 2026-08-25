package com.github.oinsio.gnomish.subprocess

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR2, NFR-P1, design D2 of bound-subprocess-commands: a drain join that is interrupted hands
 * back what it has and restores the flag,
 * rather than swallowing the interrupt on the way out of a command that is already being torn down.
 *
 * <p>Driven over a stream that blocks until this spec lets it go, so the drain is provably still
 * running when the join begins — the case the process-level specs cannot reach, since by the time
 * a killed process's pipe closes the drain has usually already finished.
 */
class DrainInterruptSpec extends Specification {

    def "FR2: an interrupted drain join returns its prefix and leaves the flag set"() {
        given: 'a stream that has spoken once and then blocks indefinitely, so the drain stays alive'
        CountDownLatch release = new CountDownLatch(1)
        // Counted down from the read that follows the one carrying the head: a drain asking for
        // more has already written what it got, so waiting on this is waiting on the capture
        // itself, with no polling of the very join under test.
        CountDownLatch delivered = new CountDownLatch(1)
        // The bulk read is the one to override: the default one loops on single bytes until the
        // buffer is full, so a stream that spoke seven bytes and then blocked would hand the drain
        // nothing at all.
        InputStream blocking = new InputStream() {
                    private byte[] head = 'partial'.bytes
                    private boolean spoken = false

                    @Override
                    int read(byte[] target, int offset, int length) {
                        if (spoken) {
                            delivered.countDown()
                            release.await()
                            return -1
                        }
                        spoken = true
                        System.arraycopy(head, 0, target, offset, head.length)
                        return head.length
                    }

                    @Override
                    int read() {
                        delivered.countDown()
                        release.await()
                        return -1
                    }
                }
        Drain drain = Drain.start(blocking, 'drain-interrupt-spec')
        assert delivered.await(10, TimeUnit.SECONDS)

        and: 'the joining thread is interrupted before the join begins, so it throws at once'
        Thread.currentThread().interrupt()

        when:
        long startedAt = System.nanoTime()
        String captured = drain.join(Duration.ofSeconds(30))
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'what the drain had read is handed back rather than lost'
        captured == 'partial'

        and: 'NFR-R3: the flag is restored, so the caller up the stack still sees the interrupt'
        Thread.interrupted()

        and: 'the join returned on the interrupt, not by sitting out its bound'
        elapsed <Duration.ofSeconds(5)

        cleanup:
        release.countDown()
        Thread.interrupted()
    }
}
