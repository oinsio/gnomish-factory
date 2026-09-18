package com.github.oinsio.gnomish.adapter.tracker.github

import spock.lang.Specification

/**
 * InstanceIdSyntax (FR10, design D11 of type-untrusted-text): the shape an instance id read back
 * off a tracker comment must have before the factory treats it as the identity of the instance
 * that wrote the marker. The gate is what lets the id stay a {@code String} two sides compare
 * rather than becoming untrusted text, so what it accepts has to be pinned as tightly as what it
 * refuses.
 */
class InstanceIdSyntaxSpec extends Specification {

    def "an ordinary operator-set instance name passes — #label"() {
        expect:
        InstanceIdSyntax.of(candidate) == Optional.of(candidate)

        where:
        label | candidate
        'the configured default shape' | 'gnomish-factory-x7k2q1'
        'a hostname' | 'build-box-07.eu.example.com'
        'an underscored name' | 'ci_runner_3'
        'a name with a plain space' | 'build box 7'
    }

    // The bound is exact: 128 characters is a name, 129 is a flood — a boundary a spec has to name,
    //     since nothing downstream would notice the gate letting one more character through.
    def "the length bound admits exactly the maximum and refuses one character more"() {
        expect:
        InstanceIdSyntax.of('n' * 128).isPresent()
        InstanceIdSyntax.of('n' * 129).isEmpty()
    }

    def "a dangerous shape is refused — #label"() {
        expect:
        InstanceIdSyntax.of(candidate).isEmpty()

        where:
        label | candidate
        'blank' | ''
        'whitespace only' | '   '
        'an ANSI escape introducer' | 'box\u001B[2K'
        'a line separator' | 'box\u2028next'
        'a bidirectional override' | 'box\u202Ename'
        'a mention' | '@everyone'
        'an issue reference' | 'box#17'
        'a fence' | 'box`whoami`'
    }
}
