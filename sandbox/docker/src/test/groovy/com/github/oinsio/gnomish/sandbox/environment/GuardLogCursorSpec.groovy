package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * D3, NFR-R1 of fix-denial-report-attachment: the daemon-side read cursor behind
 * the per-round denial delta — one nanosecond past the last timestamped line
 * (`docker logs --since` is inclusive), and "leave the cursor alone" whenever the
 * output carries no timestamp to advance to.
 */
class GuardLogCursorSpec extends Specification {

    def "the cursor is one nanosecond past the last timestamped line"() {
        expect:
        GuardLogCursor.advance(
                '2026-08-19T10:00:00.000000000Z first\n2026-08-19T10:05:00.123456789Z second\n') ==
                '2026-08-19T10:05:00.123456790Z'
    }

    // the boundary case of the reverse scan: the only line is the first one
    def "a single-line log still yields a cursor"() {
        expect:
        GuardLogCursor.advance('2026-08-19T10:00:00.000000000Z only\n') == '2026-08-19T10:00:00.000000001Z'
    }

    // mitmproxy writes unmarked chatter too; the cursor comes from the last line that has a stamp
    def "an untimestamped trailing line is skipped over"() {
        expect:
        GuardLogCursor.advance('2026-08-19T10:00:00.000000000Z denial\nno-timestamp-here\n') ==
                '2026-08-19T10:00:00.000000001Z'
    }

    // NFR-O1: --tail keeps the newest lines, so a full window means older lines were dropped by
    //     the daemon before parsing — the read is incomplete and must not read as a quiet round
    def "a read is saturated exactly at and above its tail window"() {
        expect:
        GuardLogCursor.saturated(lines(count), 10) == saturated

        where:
        count | saturated
        0 | false
        1 | false
        9 | false
        10 | true
        11 | true
    }

    private static String lines(int count) {
        (0..<count).collect {
            "2026-08-19T10:00:00.00000000${it % 10}Z line ${it}\n"
        }.join('')
    }

    def "output with no parseable timestamp leaves the cursor where it is"() {
        expect:
        GuardLogCursor.advance(raw) == null

        where:
        label | raw
        'empty' | ''
        'no timestamp' | 'plain chatter\nmore chatter\n'
        'unparseable stamp' | 'yesterday 10:00 something\n'
        'leading space only' | ' 2026-08-19T10:00:00.000000000Z indented\n'
    }
}
