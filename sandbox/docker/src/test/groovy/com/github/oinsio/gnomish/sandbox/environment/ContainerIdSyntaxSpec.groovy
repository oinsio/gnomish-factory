package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * FR10, design D11 of type-untrusted-text: the named syntax gate that lets a denial source's id
 * stay a {@code String} two leases can compare across two media, instead of a carrier whose
 * equality would include the provenance each side minted it with.
 *
 * <p>What the gate must establish is not authenticity — a daemon that lies about its own container
 * id is not a threat this answers — but inertness: what passes may be logged, put in a finding and
 * committed to a task branch as it stands.
 */
class ContainerIdSyntaxSpec extends Specification {

    def "FR10: the id shapes a daemon and the factory's own doubles produce are accepted: #candidate"() {
        expect:
        ContainerIdSyntax.of(candidate).orElseThrow() == candidate

        where:
        candidate << [
            'a' * 64,
            '3f2b1c9d8e7a',
            'sha256:container-1',
            'gnomish_guard.k1',
            'A' * 128
        ]
    }

    def "FR10: an answer that is not an id shape is refused: #why"() {
        expect:
        ContainerIdSyntax.of(candidate).isEmpty()

        where:
        why | candidate
        'empty' | ''
        'blank' | '  '
        'carries an ANSI escape' | 'c1[2J'
        'carries a line break' | "c1\n2026-01-01 ERROR forged"
        'carries a space' | 'c1 and more'
        'carries markdown a comment renderer acts on' | 'c1`#1@team'
        'longer than the bound' | 'a' * 129
    }
}
