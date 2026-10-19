package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * NFR-R1, FR2 of fix-round-stdout-drain; FR19, D17 of fix-terminal-receipt-convergence; FR6, D5 of
 * make-pipe-reads-non-parking: the teardown of the shared stdout drain on every exit path, over the
 * one {@code Process} double, whose pipe behaves as a Linux pipe — closing its read end wakes no
 * reader, so a drain that parked in a read would stay parked here on macOS too. The close is the
 * owner's stop plus the interrupt: no read is issued on an empty pipe, the close joins nothing, and
 * no drain thread is left behind, whether the post-exit bound expired, the round thread was
 * interrupted, or the round closed the drain itself.
 */
class StreamDrainTeardownSpec extends Specification {

    def clock = new VirtualClock()
    def conditions = new PollingConditions(timeout: 5)
    def child = FakeProcess.running()

    private static final String INIT =
    '{"type":"system","subtype":"init","session_id":"s-1","model":"m-1"}'

    def cleanup() {
        child.exit(0)
    }

    // FR2 of fix-round-stdout-drain; FR19, D17 (as amended 2026-10-17) of
    // fix-terminal-receipt-convergence: a drain still reading when the post-exit bound expires is
    // an infrastructure failure, never a silently partial event list; the message names the bound
    // and the bytes read, and advises raising no property (the tail-drain key is retired).
    def "await throws StreamDrainTimeoutException when the post-exit bound expires, tearing the drain down"() {
        given: 'a live child that never writes, and the drain thread it is read on'
        def reader = readerOf(child)
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, { event -> })
        conditions.eventually { assert reader.get() != null }

        when:
        drain.await(Duration.ofMillis(150))

        then:
        def e = thrown(StreamDrainTimeoutException)
        e.message.contains('post-exit bound PT0.15S')
        e.message ==~ /.*\(\d+ bytes read\).*/
        !e.message.contains('tail-drain-grace')
        !e.message.contains('raise')

        and: 'the expiring await tore the drain down itself: its thread ends while the child lives'
        ended(reader)
        child.alive
        child.stdoutPipe().reads() == 0
    }

    // FR2: an interrupted round thread is a different infrastructure failure from an
    // expired post-exit bound — the drain may have been about to finish, so the diagnostic
    // must not blame the bound.
    // Scenario: executor/agent-executor / An interrupted wait is not blamed on the bound
    def "await reports an interrupted round thread as an interruption, not an expired post-exit bound"() {
        given: 'a drain of a live child, and a round thread carrying a pending interrupt'
        def reader = readerOf(child)
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, { event -> })
        conditions.eventually { assert reader.get() != null }
        Thread.currentThread().interrupt()

        when:
        drain.await(Duration.ofSeconds(30))

        then: 'the failure names the interruption and never the bound'
        def e = thrown(StreamDrainInterruptedException)
        e.message.contains('interrupted')
        !e.message.contains('post-exit bound')

        and: 'the interrupt status is preserved for the caller'
        Thread.interrupted()

        and: 'the drain was torn down all the same'
        ended(reader)

        cleanup:
        Thread.interrupted()
    }

    // NFR-R1 of fix-round-stdout-drain; D5 of make-pipe-reads-non-parking: the close is the owner's
    // stop, seen by the view's next read within a tick — no pipe is closed, no read was parked.
    // Scenario: lifecycle/subprocess-supervision / No read is issued for bytes that are not there
    def "close stops a drain of a live child that writes nothing, with no read issued on the empty pipe"() {
        given: 'a child that lives and holds its pipe open, and a drain already querying it'
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, { event -> })
        conditions.eventually { assert child.stdoutPipe().queries() > 0 }

        when: 'the round tears the drain down, as a timeout kill path does'
        drain.close()

        then: 'the drain ends at once, though the child still lives'
        drain.await(Duration.ofSeconds(5)).isEmpty()
        child.alive

        and: 'it waited on its own tick: no read of the pipe was ever issued'
        child.stdoutPipe().reads() == 0
    }

    // NFR-R1, D5: the close's interrupt is what releases a drain whose thread is inside the
    // parser's listener, which the stop alone cannot reach until the listener returns.
    def "close releases a drain held in a listener that only an interrupt can unblock"() {
        given: 'a listener that parks interruptibly on the first line'
        def entered = new CountDownLatch(1)
        AgentProgressListener listener = { event ->
            entered.countDown()
            new CountDownLatch(1).await(30, TimeUnit.SECONDS)
        }
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, listener)
        child.writeStdout(INIT + '\n')
        entered.await(5, TimeUnit.SECONDS)

        when:
        drain.close()

        then: 'the drain finishes well before the listener would have returned on its own'
        drain.await(Duration.ofSeconds(5)).size() == 1
    }

    // D17 of fix-terminal-receipt-convergence: the post-exit bound of the round's handle is the
    // only wait; the teardown stops the drain and returns, it adds no join of its own.
    def "close returns at once, without waiting for a drain its stop has not yet ended"() {
        given: 'a listener that ignores the interrupt until the spec releases it'
        def entered = new CountDownLatch(1)
        def unblock = new CountDownLatch(1)
        AgentProgressListener listener = { event ->
            entered.countDown()
            while (unblock.count> 0) {
                try {
                    unblock.await(10, TimeUnit.SECONDS)
                } catch (InterruptedException ignored) {
                    // ignored, as a parser busy outside any wait would ignore it
                }
            }
        }
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, listener)
        child.writeStdout(INIT + '\n')
        entered.await(5, TimeUnit.SECONDS)

        when:
        long startedAt = System.nanoTime()
        drain.close()
        def took = Duration.ofNanos(System.nanoTime() - startedAt)

        then:
        took <Duration.ofMillis(500)

        when: 'the listener returns, the child still alive'
        unblock.countDown()

        then: 'the stop ends the drain: the next read of the view answers end-of-stream'
        drain.await(Duration.ofSeconds(5)).size() == 1
        child.alive
    }


    /** The thread that queries {@code process}'s stdout first: the drain thread, recorded from its own query. */
    private static AtomicReference<Thread> readerOf(FakeProcess process) {
        def reader = new AtomicReference<Thread>()
        process.stdoutPipe().onQuery { reader.set(Thread.currentThread()) }
        reader
    }

    /** Whether the recorded drain thread has ended within a bound far past a tick. */
    private static boolean ended(AtomicReference<Thread> reader) {
        reader.get().join(Duration.ofSeconds(5))
        !reader.get().alive
    }
}
