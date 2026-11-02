package com.github.oinsio.gnomish.adapter.agent.program

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.MDC
import spock.lang.Specification
import spock.lang.Timeout

/**
 * {@link ProgressLines}, the live stdout reader of a {@code streams_progress} run (FR5, NFR-R2 of
 * define-executor-contract): a JSON line is DEBUG progress, a line that is not JSON is a contract
 * violation logged and skipped — the first at WARN, the rest at DEBUG — and every chunk moves the
 * instant the silence bound is measured from. The fake has exited with its stdout waiting in the
 * pipe before the reader starts, so the reader reads it all and ends; no order between threads is
 * assumed.
 */
@Timeout(30)
class ProgressLinesSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-10-31T10:00:00Z')

    LogCaptureSupport logs = LogCaptureSupport.attach(ProgressLines, Level.DEBUG)

    def cleanup() {
        logs.detach()
    }

    private ProgressLines read(String stdout, InstantSource clock = new VirtualClock(NOW)) {
        def lines = ProgressLines.start(ChildOutput.stdout(new FakeProcess(0, stdout) {}), clock, 'acme')
        assert lines.await(Duration.ofSeconds(10))
        lines
    }

    // FR5, NFR-O2: a non-JSON line is logged once at WARN with its code, the rest at DEBUG; JSON lines are DEBUG progress
    def "FR5: the first non-JSON progress line is one WARN with its code, later ones DEBUG, and JSON lines DEBUG progress"() {
        when:
        def lines = read('{"step":1}\nnot json\n{"step":2}\nstill not json\n\n')

        then:
        lines.ended()
        def warns = logs.list.findAll { it.level == Level.WARN }
        warns.size() == 1
        warns[0].formattedMessage.startsWith(OperatorEvent.PROGRAM_PROGRESS_NOT_JSON.head())
        warns[0].formattedMessage.contains('not json')
        logs.list.count {
            it.level == Level.DEBUG && it.formattedMessage.contains('progress: {"step":')
        } == 2
        logs.list.any {
            it.level == Level.DEBUG && it.formattedMessage.contains('another non-JSON progress line: still not json')
        }
        logs.list.any {
            it.level == Level.DEBUG && it.formattedMessage.contains('wrote 2 non-JSON progress lines')
        }
    }

    // NFR-R2: the silence bound is measured from the last bytes read, the start when none arrive
    def "NFR-R2: the last activity is the start for a silent stream and the read instant of the last bytes otherwise"() {
        given: 'a clock that reads one second later each time it is asked'
        def reads = new AtomicInteger()
        def ticking = {
            -> NOW.plusSeconds(reads.getAndIncrement())
        } as InstantSource

        expect:
        read('', new VirtualClock(NOW)).lastActivity() == NOW
        read('{"step":1}\n', ticking).lastActivity().isAfter(NOW)
    }

    // FR5: an overlong line is kept to the cap, its rest read and dropped, and the next line still judged
    def "FR5: a line past the line cap is cut to the cap and the next line is still read"() {
        when:
        read('{"a":"' + 'x' * (ProgressLines.LINE_CAP_BYTES + 10) + '"}\n{"step":3}\n')

        then: 'the cut line is no JSON, the next one is progress'
        logs.list.count { it.level == Level.WARN } == 1
        logs.list.any { it.formattedMessage.contains('progress: {"step":3}') }
    }

    // FR5, NFR-O2: a cut line is reported as cut, not as a line that is not JSON — at WARN first, at DEBUG after
    def "FR5: a line past the line cap that no longer parses is named as cut, at WARN first and at DEBUG after"() {
        given:
        def overlong = '{"a":"' + 'x' * (ProgressLines.LINE_CAP_BYTES + 10) + '"}'

        when:
        read(overlong + '\n' + overlong + '\nnot json\n')

        then:
        def warns = logs.list.findAll { it.level == Level.WARN }
        warns.size() == 1
        warns[0].formattedMessage.startsWith(OperatorEvent.PROGRAM_PROGRESS_NOT_JSON.head())
        warns[0].formattedMessage.contains('over 64 KiB that was cut')
        !warns[0].formattedMessage.contains('not JSON')
        logs.list.any {
            it.level == Level.DEBUG && it.formattedMessage.contains('another progress line over 64 KiB that was cut')
        }
        logs.list.any {
            it.level == Level.DEBUG && it.formattedMessage.contains('another non-JSON progress line: not json')
        }
    }

    // FR5: a single violation is its WARN alone, with no count line; a last line without a line break is still judged
    def "FR5: one non-JSON line is one WARN and no count, and a last line with no line break is judged"() {
        when:
        read('not json\n{"step":"last"}')

        then:
        logs.list.count { it.level == Level.WARN } == 1
        !logs.list.any {
            it.formattedMessage.contains('non-JSON progress lines')
        }
        logs.list.any {
            it.formattedMessage.contains('progress: {"step":"last"}')
        }
    }

    // FR5: a line of exactly the cap is kept whole; one byte more is cut, and the cut JSON is no JSON
    def "FR5: a JSON line of #size bytes is #outcome"() {
        given:
        def line = '{"a":"' + 'x' * (size - 8) + '"}'
        assert line.length() == size

        when:
        read(line + '\n')

        then:
        logs.list.count { it.level == Level.WARN } == warns

        where:
        size || warns | outcome
        ProgressLines.LINE_CAP_BYTES || 0 | 'kept whole'
        ProgressLines.LINE_CAP_BYTES + 1 || 1 | 'cut, so not JSON'
    }

    // NFR-O2: the reader's lines carry the round's MDC, set on the thread that started it
    def "NFR-O2: progress lines carry the round's task key from the starting thread's MDC"() {
        given:
        MDC.put('taskId', 'TASK-7')

        when:
        read('{"step":1}\n')

        then:
        logs.list.find {
            it.formattedMessage.contains('progress:')
        }.MDCPropertyMap.taskId == 'TASK-7'

        cleanup:
        MDC.clear()
    }
}
