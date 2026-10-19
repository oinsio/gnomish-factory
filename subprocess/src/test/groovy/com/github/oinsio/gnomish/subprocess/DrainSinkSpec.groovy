package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.time.Duration
import spock.lang.Specification

/**
 * FR19, design D17 of fix-terminal-receipt-convergence: a reader whose retention is its own policy —
 * {@code BoundedTail}'s last lines of a command check — still reads through the one drain: the drain
 * owns the thread, the read loop, the bounded join and the stop at the bound, and hands every chunk
 * it reads to the caller's {@link DrainSink}, keeping nothing itself. Driven over
 * {@link FakeProcess} (design D6 of make-pipe-reads-non-parking).
 */
class DrainSinkSpec extends Specification implements DrainSpecSupport {

    private static final Duration BOUND = Duration.ofSeconds(10)

    def "FR19: a drain into a sink hands it every byte in order and ends it once at the end of the stream"() {
        given:
        String content = (0..<20_000).collect {
            (char) ('a' as char) + it % 26
        }.join()
        RecordingSink sink = new RecordingSink()

        when:
        Drain drain = Drain.into(ChildOutput.stdout(exitedWith(content)), 'drain-sink-spec', sink)
        boolean cut = Drain.joinAll(BOUND, drain)

        then: 'the sink received the whole stream and was told it ended'
        !cut
        sink.bytes.toString('UTF-8') == content
        sink.ends == 1

        and: 'the drain kept nothing of its own and saw no overflow'
        drain.bytes() == new byte[0]
        drain.captured() == ''
        !drain.overflowed()
        drain.failure() == null
    }

    def "FR19: a stream that breaks is a failure of the drain, not an end of the sink's stream"() {
        given:
        FakeProcess process = FakeProcess.running()
        process.writeStdout('x')
        RecordingSink sink = new RecordingSink()
        Drain drain = Drain.into(ChildOutput.stdout(process), 'drain-sink-spec-broken', sink)
        assert process.awaitStdoutRead(BOUND)
        process.inputStream.close()

        when:
        Drain.joinAll(BOUND, drain)

        then: 'what arrived before the break reached the sink, and the sink was not ended'
        sink.bytes.toString('UTF-8') == 'x'
        sink.ends == 0
        drain.failure() instanceof IOException

        cleanup:
        process.exit(0)
    }

    def "FR19: a stopped drain does not end the sink's stream"() {
        given:
        FakeProcess process = FakeProcess.running()
        RecordingSink sink = new RecordingSink()
        Drain drain = Drain.into(ChildOutput.stdout(process), 'drain-sink-spec-stopped', sink)

        when: 'the child stays alive past the bound, so the join stops the drain'
        boolean cut = Drain.joinAll(Duration.ofMillis(50), drain)

        then:
        cut
        !Drain.joinAll(BOUND, drain)
        sink.ends == 0

        cleanup:
        process.exit(0)
    }

    def "FR19: a sink that only keeps needs no end of its own"() {
        given:
        List<Integer> lengths = []
        DrainSink sink = { byte[] chunk, int length ->
            lengths << length
        } as DrainSink

        when:
        Drain.joinAll(BOUND, Drain.into(ChildOutput.stdout(exitedWith('abc')), 'drain-sink-spec-lambda', sink))

        then:
        lengths == [3]
    }

    private static final class RecordingSink implements DrainSink {

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream()
        volatile int ends

        @Override
        void keep(byte[] chunk, int length) {
            bytes.write(chunk, 0, length)
        }

        @Override
        void end() {
            ends++
        }
    }
}
