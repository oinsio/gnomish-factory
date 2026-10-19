package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.MDC
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * FR1, FR2, FR4, D1, D3, D4, D5 of fix-round-stdout-drain; D6 of make-pipe-reads-non-parking: the
 * shared stdout gobbler's reading, over the one {@code Process} double, whose pipe behaves as a
 * Linux pipe. Covers reading from construction (while the child lives), the tail delivered by
 * {@code await} after the exit, live listener callbacks on the drain thread under the round's
 * MDC, byte accounting, a read failure, and a stream past any pipe buffer. The teardown is
 * {@link StreamDrainTeardownSpec}'s.
 */
class StreamDrainSpec extends Specification {

    def clock = new VirtualClock()
    def conditions = new PollingConditions(timeout: 5)
    def child = FakeProcess.running()

    private static final String INIT =
    '{"type":"system","subtype":"init","session_id":"s-1","model":"m-1"}'
    private static final String TOOL =
    '{"type":"assistant","session_id":"s-1","message":{"id":"m1","model":"m-1","content":[{"type":"tool_use","id":"t1","name":"Write","input":{}}]}}'
    private static final String RESULT =
    '{"type":"result","subtype":"success","session_id":"s-1","result":"done"}'

    def cleanup() {
        child.exit(0)
    }

    // FR1: the drain reads while the child lives — no waiting for its exit.
    def "parses lines as they arrive, while the child still runs"() {
        given:
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, { event -> })

        when: 'two lines are written while the child stays alive'
        write(INIT, TOOL)

        then: 'they are already accounted for'
        conditions.eventually { assert drain.bytesRead() > 0 }

        when: 'the child writes its result, exits, and the round awaits the drain'
        write(RESULT)
        child.exit(0)
        def events = drain.await(Duration.ofSeconds(5))

        then: 'every event, including the tail written last, is delivered'
        events.size() == 3
        events.last().event() instanceof AgentEvent.ResultEvent

        cleanup:
        drain.close()
    }

    // FR2, D2: the tail already in the pipe at exit is what the post-exit bound absorbs.
    // Scenario: executor/agent-executor / The round waits for its drain after exit
    def "await returns the tail written just before the exit"() {
        given:
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, { event -> })

        when:
        write(INIT, TOOL, RESULT)
        child.exit(0)
        def events = drain.await(Duration.ofSeconds(5))

        then:
        events.collect { it.event().getClass().simpleName } == [
            'InitEvent',
            'AssistantEvent',
            'ResultEvent'
        ]

        cleanup:
        drain.close()
    }

    // FR4, UX1: callbacks fire per line, from the drain thread, while the child lives.
    // Scenario: executor/agent-executor / Progress is live, not post-mortem
    // Scenario: executor/agent-executor / Listeners are invoked from the drain thread
    def "emits progress events live, from the drain thread, before the exit"() {
        given: 'a listener recording the event and the thread it arrived on'
        def recorded = [].asSynchronized()
        def threads = [].asSynchronized()
        AgentProgressListener listener = { event ->
            recorded << event
            threads << Thread.currentThread()
        }
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, listener)

        when:
        write(INIT, TOOL)

        then: 'both progress events are observed before any exit'
        conditions.eventually {
            assert recorded.size() == 2
            assert recorded[0] instanceof AgentProgressEvent.RoundStarted
            assert recorded[1] == new AgentProgressEvent.ToolStarted(UntrustedText.agent('Write'))
        }

        and: 'they arrived on the drain thread, not the round thread'
        threads.every { it !== Thread.currentThread() }
        threads.every { it.name == 'agent-stdout-drain' }

        cleanup:
        drain.close()
    }

    // D4: MDC is thread-local, so the round's log scope is captured and re-applied.
    // Scenario: executor/agent-executor / The log renderer writes the feed under the attempt's keys
    def "applies the round thread's MDC inside the drain thread"() {
        given: 'the round thread is inside an attempt MDC scope'
        MDC.put('taskId', 'TASK-7')
        def seen = new AtomicReference<String>()
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, { event ->
            seen.set(MDC.get('taskId'))
        })

        when:
        write(INIT)

        then:
        conditions.eventually { assert seen.get() == 'TASK-7' }

        cleanup:
        drain.close()
        MDC.clear()
    }

    // D5: byte accounting is what the missing-result diagnostic reports.
    def "counts the raw bytes read off the stream"() {
        given:
        def payload = INIT + '\n' + RESULT + '\n'
        write(INIT, RESULT)
        child.exit(0)
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, { event -> })

        when:
        drain.await(Duration.ofSeconds(5))

        then:
        drain.bytesRead() == payload.bytes.length

        cleanup:
        drain.close()
    }

    // D3: a genuine I/O failure of the stream propagates from await.
    def "await propagates a read failure as UncheckedIOException"() {
        given: 'a read end that refuses every query, as a closed FileInputStream does'
        child.inputStream.close()
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, { event -> })

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
        write(INIT + '\n' + noise + RESULT)
        child.exit(0)
        def drain = StreamDrain.start(ChildOutput.stdout(child), clock, { event -> })

        when:
        def events = drain.await(Duration.ofSeconds(10))

        then:
        drain.bytesRead() > 1024 * 1024
        events.last().event() instanceof AgentEvent.ResultEvent

        cleanup:
        drain.close()
    }

    private void write(String... lines) {
        lines.each { child.writeStdout(it + '\n') }
    }
}
