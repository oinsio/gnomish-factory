package com.github.oinsio.gnomish.adapter.agent

import java.time.Instant
import spock.lang.Specification

/**
 * FR5, UX2, D5 of fix-round-stdout-drain: the missing-result failure reports how
 * much of the stream was read, and names probable truncation when that volume
 * sits at an OS pipe-buffer boundary — so a human can tell "the agent emitted no
 * result" apart from "the stream was cut short" without reading adapter source.
 */
class MissingResultEventDiagnosticsSpec extends Specification {

    // FR5: bytes and event count travel in the message.
    def "message carries the bytes and events read"() {
        when:
        def e = new MissingResultEventException('s-1', 4096L, 12)

        then:
        e.message.contains('session: s-1')
        e.message.contains('read 4096 bytes, 12 event(s)')
    }

    // UX2: the observed repro read 65 528 of 65 536 bytes — one buffered line short of the boundary.
    def "adds the truncation hint when the volume sits at a pipe-buffer boundary: #bytes bytes"() {
        expect:
        new MissingResultEventException('s-1', bytes, 3).message.contains('probably truncated')

        where:
        bytes << [
            65_528L,
            65_536L,
            131_072L,
            196_600L,
            57_344L
        ]
    }

    // UX2: an ordinary short or mid-buffer volume must not cry truncation.
    def "omits the truncation hint away from a boundary: #bytes bytes"() {
        expect:
        !new MissingResultEventException('s-1', bytes, 3).message.contains('probably truncated')

        where:
        bytes << [0L, 512L, 40_000L, 100_000L]
    }

    // FR5: a round that read nothing at all still reports its volume — "0 bytes" is the
    // diagnostic, distinct from a caller that kept no accounting.
    def "reports a zero-byte read rather than treating it as unknown"() {
        expect:
        new MissingResultEventException('s-1', 0L, 0).message.contains('read 0 bytes, 0 event(s)')
    }

    // FR5: callers with no byte accounting keep the plain message.
    def "omits the volume clause when the read volume is unknown"() {
        when:
        def e = new MissingResultEventException('s-1')

        then:
        e.message == 'stream-json carried no result event for round (session: s-1)'
    }

    // D5: the extractor is the raise site the drain's accounting flows through.
    def "the extractor reports the drain's volume for a result-less event list"() {
        given:
        def events = [
            new TimestampedEvent(new AgentEvent.InitEvent('s-9', 'm-1'), Instant.EPOCH)
        ]

        when:
        new AgentRoundResultExtractor().extract(events, Instant.EPOCH, 65_528L)

        then:
        def e = thrown(MissingResultEventException)
        e.message.contains('session: s-9')
        e.message.contains('read 65528 bytes, 1 event(s)')
        e.message.contains('probably truncated')
    }
}
