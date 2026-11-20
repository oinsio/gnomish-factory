package com.github.oinsio.gnomish.pendingkey

import spock.lang.Specification

/**
 * FR2 of define-executor-contract; FR2, FR3 of migrate-verify-to-executor-contract (design D20):
 * the pending key refuses a value no environment variable can carry, accepts one up to its cap,
 * and prints as its value.
 */
class PendingKeySpec extends Specification {

    def "FR2: a pending key prints as its value"() {
        expect:
        new PendingKey('build-8812').toString() == 'build-8812'
    }

    def "FR2: a blank pending key '#key' is refused"() {
        when:
        new PendingKey(key)

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == 'pendingKey must not be blank'

        where:
        key << ['', ' ', '\t ']
    }

    def "FR2: a key of exactly MAX_LENGTH characters is accepted"() {
        given:
        def value = 'k' * PendingKey.MAX_LENGTH

        expect:
        PendingKey.MAX_LENGTH == 4096
        new PendingKey(value).value() == value
        PendingKey.unusable(value) == null
    }

    def "FR2, NFR-R3: a pending key that #why is refused, so no unusable key reaches GNOMISH_PENDING_KEY"() {
        when:
        new PendingKey(key)

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == "pendingKey is unusable: ${why}"
        PendingKey.unusable(key) == why

        where:
        why | key
        "it is longer than ${PendingKey.MAX_LENGTH} characters" | 'k' * (PendingKey.MAX_LENGTH + 1)
        'it holds a control character' | 'a\u0000b'
        'it holds a control character' | 'a\u001bb'
        'it holds a control character' | 'a\u007fb'
        'it holds a control character' | 'a\u0085b'
        'it holds a control character' | 'a\nb'
    }

    def "FR2: every ISO control character is refused"() {
        given:
        def controls = (0..0xFFFF).findAll {
            Character.isISOControl(it as char)
        }

        expect:
        controls.size() == 65
        controls.every {
            PendingKey.unusable("a${it as char}b") == 'it holds a control character'
        }
    }

    def "FR2: a key holding no control character is usable"() {
        expect:
        PendingKey.unusable('build-8812 ü/✓') == null
    }
}
