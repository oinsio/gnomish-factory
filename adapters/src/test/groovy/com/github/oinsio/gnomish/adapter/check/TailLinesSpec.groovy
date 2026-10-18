package com.github.oinsio.gnomish.adapter.check

import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR19 and design D17 of fix-terminal-receipt-convergence; FR7, D6 of add-manual-run: the tail's
 * retention policy, fed chunk by chunk as the one drain reads, splits lines where a line reader
 * would — the drain hands it bytes, not lines — and keeps an unterminated last line only when the
 * stream ended. The line-count and byte caps are pinned through the real runner in {@code
 * CommandProcessRunnerSpec}.
 */
class TailLinesSpec extends Specification {

    def "a line ends at a newline, a carriage return or both together, as a line reader ends it"() {
        given:
        def tail = fed('crlf\r\nlone\rplain\n\nlast')

        when:
        tail.end()

        then: 'the CR of a CRLF pair ends one line, not two, and the unterminated last line is kept'
        tail.snapshot() == [
            'crlf',
            'lone',
            'plain',
            '',
            'last'
        ].join('\n')
    }

    def "an unterminated last line is kept only when the stream ended"() {
        given:
        def tail = fed('done\npartial')

        expect: 'before the end - a cut stream - the partial line is not a line'
        tail.snapshot() == 'done'

        when:
        tail.end()

        then:
        tail.snapshot() == 'done\npartial'
    }

    def "a stream that ends on its terminator adds no empty last line"() {
        given:
        def tail = fed('one\r')

        when:
        tail.end()

        then:
        tail.snapshot() == 'one'
    }

    def "a newline after a carriage return that ended a line is a new line again once a byte came between"() {
        given: 'CR, a byte, then LF: the LF ends a second line'
        def tail = fed('a\rb\n\n')

        expect:
        tail.snapshot() == ['a', 'b', ''].join('\n')
    }

    def "a multi-byte character split across two chunks is decoded whole"() {
        given:
        byte[] bytes = 'xü\n'.getBytes(StandardCharsets.UTF_8)
        def tail = new TailLines()

        when: 'the two bytes of the character arrive in different reads'
        tail.keep(bytes[0..1] as byte[], 2)
        tail.keep(bytes[2..<bytes.length] as byte[], bytes.length - 2)

        then:
        tail.snapshot() == 'xü'
    }

    def "only the first length bytes of a reused chunk are taken"() {
        given:
        def tail = new TailLines()

        when:
        tail.keep('ab\nSTALE'.bytes, 3)

        then:
        tail.snapshot() == 'ab'
    }

    private static TailLines fed(String text) {
        def tail = new TailLines()
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8)
        tail.keep(bytes, bytes.length)
        tail
    }
}
