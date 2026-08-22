package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.MDC
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * FR1, FR2, FR4, NFR-R1, D1, D3, D4, D5 of fix-round-stdout-drain: the shared
 * stdout gobbler. Covers reading from construction (before the writer has
 * finished, let alone "exited"), the tail delivered by {@code await} after the
 * writer closed, live listener callbacks on the drain thread under the round's
 * MDC, byte accounting, the grace expiry infrastructure failure, and the
 * no-thread-outlives-the-round teardown on every exit path.
 */
class StreamDrainSpec extends Specification {

    def clock = new VirtualClock()
    def conditions = new PollingConditions(timeout: 5)

    private static final String INIT =
    '{"type":"system","subtype":"init","session_id":"s-1","model":"m-1"}'
    private static final String TOOL =
    '{"type":"assistant","session_id":"s-1","message":{"id":"m1","model":"m-1","content":[{"type":"tool_use","id":"t1","name":"Write","input":{}}]}}'
    private static final String RESULT =
    '{"type":"result","subtype":"success","session_id":"s-1","result":"done"}'

    // FR1: the drain reads while the stream is still open — no waiting for "exit".
    def "parses lines as they arrive, before the stream is closed"() {
        given: 'a pipe standing in for a still-running process stdout'
        def sink = new PipedOutputStream()
        def source = new PipedInputStream(sink, 4096)
        def drain = StreamDrain.start(source, clock, { event -> })

        when: 'two lines are written but the writer stays open'
        write(sink, INIT, TOOL)

        then: 'they are already accounted for while the "process" is still alive'
        conditions.eventually { assert drain.bytesRead() > 0 }

        when: 'the writer finishes and the round awaits the drain'
        write(sink, RESULT)
        sink.close()
        def events = drain.await(Duration.ofSeconds(5))

        then: 'every event, including the tail written last, is delivered'
        events.size() == 3
        events.last().event() instanceof AgentEvent.ResultEvent

        cleanup:
        drain.close()
    }

    // FR2, D2: the tail already in the pipe at exit is what the grace absorbs.
    def "await returns the tail written just before the stream closed"() {
        given:
        def sink = new PipedOutputStream()
        def source = new PipedInputStream(sink, 65536)
        def drain = StreamDrain.start(source, clock, { event -> })

        when:
        write(sink, INIT, TOOL, RESULT)
        sink.close()
        def events = drain.await(Duration.ofSeconds(5))

        then:
        events.collect { it.event().getClass().simpleName } ==
        [
            'InitEvent',
            'AssistantEvent',
            'ResultEvent'
        ]

        cleanup:
        drain.close()
    }

    // FR4, UX1: callbacks fire per line, from the drain thread, while the writer is open.
    def "emits progress events live, from the drain thread, before the stream closes"() {
        given: 'a listener recording the event and the thread it arrived on'
        def recorded = [].asSynchronized()
        def threads = [].asSynchronized()
        AgentProgressListener listener = { event ->
            recorded << event
            threads << Thread.currentThread()
        }
        def sink = new PipedOutputStream()
        def source = new PipedInputStream(sink, 4096)
        def drain = StreamDrain.start(source, clock, listener)

        when: 'the init and tool lines are written, the writer staying open'
        write(sink, INIT, TOOL)

        then: 'both progress events are observed before any exit'
        conditions.eventually {
            assert recorded.size() == 2
            assert recorded[0] instanceof AgentProgressEvent.RoundStarted
            assert recorded[1] == new AgentProgressEvent.ToolStarted('Write')
        }

        and: 'they arrived on the drain thread, not the round thread'
        threads.every { it !== Thread.currentThread() }
        threads.every { it.name == 'agent-stdout-drain' }

        cleanup:
        sink.close()
        drain.close()
    }

    // D4: MDC is thread-local, so the round's log scope is captured and re-applied.
    def "applies the round thread's MDC inside the drain thread"() {
        given: 'the round thread is inside an attempt MDC scope'
        MDC.put('taskId', 'TASK-7')
        def seen = new AtomicReference<String>()
        AgentProgressListener listener = { event ->
            seen.set(MDC.get('taskId'))
        }
        def sink = new PipedOutputStream()
        def source = new PipedInputStream(sink, 4096)
        def drain = StreamDrain.start(source, clock, listener)

        when:
        write(sink, INIT)

        then:
        conditions.eventually { assert seen.get() == 'TASK-7' }

        cleanup:
        sink.close()
        drain.close()
        MDC.clear()
    }

    // D5: byte accounting is what the missing-result diagnostic reports.
    def "counts the raw bytes read off the stream"() {
        given:
        def payload = (INIT + '\n' + RESULT + '\n').getBytes(StandardCharsets.UTF_8)
        def drain = StreamDrain.start(new ByteArrayInputStream(payload), clock, { event -> })

        when:
        drain.await(Duration.ofSeconds(5))

        then:
        drain.bytesRead() == payload.length

        cleanup:
        drain.close()
    }

    // FR2: a drain still reading when the grace expires is an infrastructure failure,
    // never a silently partial event list.
    def "await throws StreamDrainTimeoutException when the grace expires, tearing the drain down"() {
        given: 'a stream that never ends and ignores interrupts'
        def released = new AtomicBoolean()
        def drain = StreamDrain.start(spinningStream(released), clock, { event -> })

        when:
        drain.await(Duration.ofMillis(150))

        then:
        def e = thrown(StreamDrainTimeoutException)
        e.message.contains('PT0.15S')
        e.message.contains('tail-drain-grace')

        and: 'the expiring await tore the drain down itself — the stream is closed'
        released.get()

        cleanup:
        drain.close()
    }

    // FR2: an interrupted round thread is a different infrastructure failure from an
    // expired grace — the drain may have been about to finish, so the diagnostic must
    // not blame the grace or advise raising it.
    def "await reports an interrupted round thread as an interruption, not an expired grace"() {
        given: 'a drain that would need far longer than the round thread is willing to wait'
        def released = new AtomicBoolean()
        def drain = StreamDrain.start(spinningStream(released), clock, { event -> })

        and: 'the round thread carries a pending interrupt'
        Thread.currentThread().interrupt()

        when:
        drain.await(Duration.ofSeconds(30))

        then: 'the failure names the interruption and never the grace'
        def e = thrown(StreamDrainInterruptedException)
        !e.message.contains('tail-drain-grace')

        and: 'the interrupt status is preserved for the caller'
        Thread.interrupted()

        and: 'the drain was torn down all the same'
        released.get()

        cleanup:
        Thread.interrupted()
        drain.close()
    }

    // NFR-R1: the teardown's stream close is what releases a drain parked on a read
    // that no interrupt can reach — a pipe whose writer is a killed process.
    def "close releases a drain that only its stream's close can unblock"() {
        given:
        def closed = new AtomicBoolean()
        def drain = StreamDrain.start(spinningStream(closed), clock, { event -> })

        when: 'the round tears the drain down, as a timeout kill path does'
        drain.close()

        then: 'the stream is closed'
        closed.get()

        and: 'the drain thread has finished — a one-millisecond await no longer times out'
        drain.await(Duration.ofMillis(1)).isEmpty()
    }

    // NFR-R1: and the teardown's interrupt is what releases a drain blocked on a read
    // that its stream's close does not unblock.
    def "close releases a drain that only an interrupt can unblock"() {
        given: 'a stream whose read parks interruptibly and whose close does not release it'
        def source = new InputStream() {
                    @Override
                    int read() {
                        try {
                            Thread.sleep(30_000)
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt()
                        }
                        -1
                    }

                    @Override
                    void close() {
                        // deliberately inert: only the interrupt can end this read
                    }
                }
        def drain = StreamDrain.start(source, clock, { event -> })

        when:
        drain.close()

        then: 'the drain thread has finished'
        drain.await(Duration.ofMillis(1)).isEmpty()
    }

    // D3: a genuine I/O failure while the process is alive propagates from await.
    def "await propagates a read failure as UncheckedIOException"() {
        given:
        def source = new InputStream() {
                    @Override
                    int read() throws IOException {
                        throw new IOException('pipe exploded')
                    }
                }
        def drain = StreamDrain.start(source, clock, { event -> })

        when:
        drain.await(Duration.ofSeconds(5))

        then:
        def e = thrown(UncheckedIOException)
        e.message == 'could not read agent process stdout'

        cleanup:
        drain.close()
    }

    // FR1: a stream far past any OS pipe buffer is fully consumed.
    def "consumes a stream larger than the OS pipe buffer"() {
        given: 'over a megabyte of noise followed by the result event'
        def noise = ('{"type":"assistant","session_id":"s-1","message":{"id":"m","model":"m-1",' +
                '"content":[{"type":"text","text":"' + ('x' * 900) + '"}]}}\n') * 1200
        def payload = (INIT + '\n' + noise + RESULT + '\n').getBytes(StandardCharsets.UTF_8)
        def drain = StreamDrain.start(new ByteArrayInputStream(payload), clock, { event -> })

        when:
        def events = drain.await(Duration.ofSeconds(10))

        then:
        drain.bytesRead() > 1024 * 1024
        events.last().event() instanceof AgentEvent.ResultEvent

        cleanup:
        drain.close()
    }

    /**
     * A stream whose read neither ends nor answers an interrupt — only its own
     * {@code close} releases it, standing in for a pipe an interrupt cannot reach.
     * The spin is bounded so a mutant that never closes it still frees the thread.
     */
    private static InputStream spinningStream(AtomicBoolean released) {
        new InputStream() {
                    @Override
                    int read() {
                        long deadline = System.nanoTime() + 10_000_000_000L
                        while (!released.get() && System.nanoTime() <deadline) {
                            Thread.onSpinWait()
                        }
                        -1
                    }

                    @Override
                    void close() {
                        released.set(true)
                    }
                }
    }

    private static void write(PipedOutputStream sink, String... lines) {
        lines.each { sink.write((it + '\n').getBytes(StandardCharsets.UTF_8)) }
        sink.flush()
    }
}
