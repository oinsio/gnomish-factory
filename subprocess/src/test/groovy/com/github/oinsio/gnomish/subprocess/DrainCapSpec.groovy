package com.github.oinsio.gnomish.subprocess

import java.time.Duration
import spock.lang.Specification

/**
 * FR19, design D17 of fix-terminal-receipt-convergence: a drain may keep at most a capacity of the
 * bytes it reads — the shape {@code GitExec}'s stdout cap is applied on — while still reading the
 * stream to its end, so the child never blocks on a full pipe, and it reports whether the stream
 * carried more than it kept. The cap's policy (what a value means, what an overflow means) stays
 * with the caller; the drain only counts.
 */
class DrainCapSpec extends Specification {

    private static final Duration BOUND = Duration.ofSeconds(10)

    def "FR19: a capacity of #capacity over #length bytes keeps #kept bytes and overflowed is #overflowed"() {
        given:
        byte[] content = ('x' * length).bytes
        CountingStream stream = new CountingStream(content)

        when:
        Drain drain = Drain.capped(stream, 'drain-cap-spec', capacity)
        boolean cut = Drain.joinAll(BOUND, drain)

        then: 'the drain ended at end-of-file, having read the whole stream'
        !cut
        stream.exhausted

        and: 'it kept a prefix of the capacity and said whether more arrived'
        drain.bytes() == content[0..<kept] as byte[]
        drain.overflowed() == overflowed

        where:
        capacity | length || kept | overflowed
        0 | 0 || 0 | false
        0 | 1 || 0 | true
        5 | 3 || 3 | false
        5 | 5 || 5 | false
        5 | 6 || 5 | true
        8192 | 20_000 || 8192 | true
    }

    def "FR19: an uncapped drain keeps every byte and never overflows"() {
        given:
        byte[] content = ('y' * 20_000).bytes

        when:
        Drain drain = Drain.start(new ByteArrayInputStream(content), 'drain-cap-spec-uncapped')
        Drain.joinAll(BOUND, drain)

        then:
        drain.bytes() == content
        drain.captured() == 'y' * 20_000
        !drain.overflowed()
    }

    def "FR19: a negative capacity is refused rather than read as no cap"() {
        when:
        Drain.capped(new ByteArrayInputStream(new byte[0]), 'drain-cap-spec-negative', -1)

        then:
        thrown(IllegalArgumentException)
    }

    /** A stream that says whether it was read to the end, one byte at a time on the single-byte path. */
    private static final class CountingStream extends ByteArrayInputStream {

        CountingStream(byte[] content) {
            super(content)
        }

        boolean isExhausted() {
            available() == 0
        }
    }
}
