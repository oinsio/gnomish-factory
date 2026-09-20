package com.github.oinsio.gnomish.adapter.agent

import spock.lang.Specification

/**
 * FR10, design D11 of type-untrusted-text: the named syntax gate that lets the round's model id
 * stay a {@code String} where it is used — a key of the token map that reaches the domain's
 * {@code ExecutorUsage}, {@code state.json} and the dashboard.
 *
 * <p>What the gate establishes is inertness, not authenticity: what passes may be written to a
 * wire format and displayed as it stands, and what does not is replaced by one bounded
 * factory-authored constant rather than dropped, so a round's telemetry still reports that tokens
 * were spent.
 */
class ModelIdSyntaxSpec extends Specification {

    def "FR10: the ids a real CLI reports are accepted as they are: #candidate"() {
        expect:
        ModelIdSyntax.of(candidate) == candidate

        where:
        candidate << [
            'claude-opus-4-8[1m]',
            'claude-haiku-4-5-20251001',
            'vendor/model:2025-01-01',
            'a',
            'a' * 128
        ]
    }

    def "FR10: an id that could drive a terminal or forge a record degrades to the placeholder: #why"() {
        expect:
        ModelIdSyntax.of(candidate) == ModelIdSyntax.UNUSABLE

        where:
        why | candidate
        'empty' | ''
        'carries an ANSI escape' | 'claude-x[2J'
        'carries a line break' | "claude-x\n2026-01-01 ERROR forged record"
        'carries a space' | 'claude x'
        'carries a markdown mention' | 'claude-x @team #1'
        'longer than the bound' | 'a' * 129
    }

    def "FR10: the placeholder is bounded and says what it stands for"() {
        expect:
        ModelIdSyntax.UNUSABLE.length() < 40
        ModelIdSyntax.UNUSABLE.contains('model id')
    }
}
