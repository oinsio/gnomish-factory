package com.github.oinsio.gnomish.adapter.agent

import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR5, D5 of fix-round-stdout-drain: the byte accounting under the drain's
 * reader — the volume figure a missing-result failure reports. Covers both read
 * shapes (single byte, bulk), the end-of-stream sentinel not being counted, and
 * the count surviving a partial bulk read.
 */
class CountingInputStreamSpec extends Specification {

    def "counts bytes read one at a time and stops at end of stream"() {
        given:
        def counting = new CountingInputStream(new ByteArrayInputStream('abc'.getBytes(StandardCharsets.UTF_8)))

        when: 'the stream is read to exhaustion, sentinel included'
        def read = (1..4).collect { counting.read() }

        then: 'every byte is passed through unchanged, then the -1 sentinel'
        read == [
            'a' as char as int,
            'b' as char as int,
            'c' as char as int,
            -1
        ]

        and: 'only the three real bytes are counted, never the sentinel'
        counting.count() == 3
    }

    def "counts bytes read in bulk"() {
        given:
        def counting = new CountingInputStream(new ByteArrayInputStream('hello world'.getBytes(StandardCharsets.UTF_8)))
        byte[] buffer = new byte[64]

        when:
        int read = counting.read(buffer, 0, buffer.length)

        then:
        read == 11
        counting.count() == 11

        when: 'the exhausted stream is read again'
        counting.read(buffer, 0, buffer.length)

        then: 'the end-of-stream sentinel adds nothing'
        counting.count() == 11
    }

    def "accumulates across successive partial bulk reads"() {
        given:
        def counting = new CountingInputStream(new ByteArrayInputStream('0123456789'.getBytes(StandardCharsets.UTF_8)))
        byte[] buffer = new byte[4]

        when:
        counting.read(buffer, 0, 4)
        counting.read(buffer, 0, 4)

        then:
        counting.count() == 8
    }

    def "counts nothing for an empty stream"() {
        given:
        def counting = new CountingInputStream(new ByteArrayInputStream(new byte[0]))

        when:
        counting.read()

        then:
        counting.count() == 0
    }
}
