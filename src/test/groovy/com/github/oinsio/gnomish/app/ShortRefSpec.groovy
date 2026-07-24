package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * ShortRef (FR9 of add-tracker-port): recognizes a bare or `#`-prefixed non-negative integer as a
 * short ref, distinct from an already-canonical id or any malformed string.
 *
 * Implements FR9 of add-tracker-port.
 */
class ShortRefSpec extends Specification {

    def "recognizes a bare integer as a short ref: #ref"() {
        expect:
        ShortRef.isShortRef(ref)

        where:
        ref << ['42', '0', '1', '999999']
    }

    def "recognizes a hash-prefixed integer as a short ref: #ref"() {
        expect:
        ShortRef.isShortRef(ref)

        where:
        ref << ['#42', '#0', '#1']
    }

    def "does not recognize an already-canonical id or malformed string: #ref"() {
        expect:
        !ShortRef.isShortRef(ref)

        where:
        ref << [
            'github:acme/widgets#42',
            'abc',
            '',
            '#',
            '42#',
            '#42#',
            '4a2',
            '-42',
            ' 42',
            '42 '
        ]
    }

    def "extracts the issue number from a bare short ref"() {
        expect:
        ShortRef.issueNumberOf('42') == 42
    }

    def "extracts the issue number from a hash-prefixed short ref"() {
        expect:
        ShortRef.issueNumberOf('#42') == 42
    }
}
