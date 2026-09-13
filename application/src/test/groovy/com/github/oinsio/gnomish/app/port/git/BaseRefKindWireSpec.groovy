package com.github.oinsio.gnomish.app.port.git

import spock.lang.Specification

/**
 * FR7, D7 of add-base-ref-resolution (revised 2026-09-10): {@link BaseRefKind} is a wire
 * vocabulary — the pin records which namespace origin held a base name in, and a resume reads it
 * back to fetch that namespace only. The mandatory round-trip contract every wire vocabulary in
 * this project carries (`.claude/rules/testing.md`) therefore applies to it.
 *
 * <p>Here rather than beside the {@code task.json} mapper that writes the token: the vocabulary is
 * an {@code :application} type, and mutation scope is per module — a spec one module down leaves
 * these methods uncovered in this one.
 */
class BaseRefKindWireSpec extends Specification {

    // FR7: every constant survives the write/read pair — iterated, never a hand-listed subset, so
    //     a constant added on one side only fails here rather than in production.
    def "every kind round-trips through its wire token (#kind)"() {
        expect:
        BaseRefKind.fromWire((kind as BaseRefKind).wireValue()) == kind

        where:
        kind << BaseRefKind.values()
    }

    // FR7: the tokens themselves are part of the contract — a document written by one build is read
    //     by another, so the spelling is pinned, not merely round-tripped.
    def "the wire token of #kind is '#token'"() {
        expect:
        kind.wireValue() == token

        where:
        kind | token
        BaseRefKind.BRANCH | 'branch'
        BaseRefKind.TAG | 'tag'
        BaseRefKind.COMMIT | 'commit'
    }

    // FR7: the documented forward-compatibility arm. An absent token and one this build does not
    //     recognize both read as "no kind" — the shape a manual pin carries, which resumes by
    //     classifying the name against origin exactly as a pre-kind pin does. There is deliberately
    //     no UNKNOWN constant: this vocabulary chooses a fetch namespace, so a value no fetch could
    //     act on would have to be re-checked at every use.
    def "an absent or unrecognized token reads as no kind (#token)"() {
        expect:
        BaseRefKind.fromWire(token) == null

        where:
        token << [
            null,
            '',
            ' ',
            'BRANCH',
            'Tag',
            'worktree',
            'refs/heads/main'
        ]
    }
}
