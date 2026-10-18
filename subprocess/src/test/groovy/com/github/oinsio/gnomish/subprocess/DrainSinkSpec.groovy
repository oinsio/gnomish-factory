package com.github.oinsio.gnomish.subprocess

import java.time.Duration
import spock.lang.Specification

/**
 * FR19, design D17 of fix-terminal-receipt-convergence: a reader whose retention is its own policy —
 * {@code BoundedTail}'s last lines of a command check — still reads through the one drain: the drain
 * owns the thread, the read loop, the bounded join and the close at the bound, and hands every chunk
 * it reads to the caller's {@link DrainSink}, keeping nothing itself.
 */
class DrainSinkSpec extends Specification {

    private static final Duration BOUND = Duration.ofSeconds(10)

    def "FR19: a drain into a sink hands it every byte in order and ends it once at end-of-file"() {
        given:
        byte[] content = (0..<20_000).collect {
            (byte) ('a' as char) + it % 26
        } as byte[]
        RecordingSink sink = new RecordingSink()

        when:
        Drain drain = Drain.into(new ByteArrayInputStream(content), 'drain-sink-spec', sink)
        boolean cut = Drain.joinAll(BOUND, drain)

        then: 'the sink received the whole stream and was told it ended'
        !cut
        sink.bytes.toByteArray() == content
        sink.ends == 1

        and: 'the drain kept nothing of its own and saw no overflow'
        drain.bytes() == new byte[0]
        drain.captured() == ''
        !drain.overflowed()
        drain.failure() == null
    }

    def "FR19: a stream that breaks is a failure of the drain, not an end of the sink's stream"() {
        given:
        IOException broken = new IOException('pipe broke')
        InputStream stream = new InputStream() {
                    int served

                    @Override
                    int read() {
                        throw new UnsupportedOperationException('the drain reads in chunks')
                    }

                    @Override
                    int read(byte[] b, int off, int len) {
                        if (served++ == 0) {
                            b[off] = (byte) ('x' as char)
                            return 1
                        }
                        throw broken
                    }
                }
        RecordingSink sink = new RecordingSink()

        when:
        Drain drain = Drain.into(stream, 'drain-sink-spec-broken', sink)
        Drain.joinAll(BOUND, drain)

        then: 'what arrived before the break reached the sink, and the sink was not ended'
        sink.bytes.toString('UTF-8') == 'x'
        sink.ends == 0
        drain.failure().is(broken)
    }

    def "FR19: a sink that only keeps needs no end of its own"() {
        given:
        List<Integer> lengths = []
        DrainSink sink = { byte[] chunk, int length ->
            lengths << length
        } as DrainSink

        when:
        Drain.joinAll(BOUND, Drain.into(new ByteArrayInputStream('abc'.bytes), 'drain-sink-spec-lambda', sink))

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
