package com.github.oinsio.gnomish.adapter.environment

import spock.lang.Specification

/**
 * FR9, D6 of add-sandbox-core (task 7.1): the layered positive child-environment
 * allowlist — base ∪ passthrough ∪ factory-set with nothing inherited implicitly,
 * values read live from the factory environment at compose time, a credential
 * name in passthrough refused at construction naming the variable, and declared
 * credential names never present in a composed map.
 */
class ChildEnvAllowlistSpec extends Specification {

    def "FR9: a passthrough name declared as a credential is refused at construction, naming the variable"() {
        when:
        ChildEnvAllowlist.of([
            'JAVA_HOME',
            'GNOMISH_GITHUB_TOKEN'
        ], ['GNOMISH_GITHUB_TOKEN'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('GNOMISH_GITHUB_TOKEN')
        e.message.contains('factory.sandbox.env-passthrough')
        !e.message.contains('JAVA_HOME')
    }

    def "FR9: every offending passthrough name is named, not just the first"() {
        when:
        ChildEnvAllowlist.of(['TOKEN_A', 'SAFE', 'TOKEN_B'], ['TOKEN_A', 'TOKEN_B'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('TOKEN_A')
        e.message.contains('TOKEN_B')
        !e.message.contains('SAFE')
    }

    def "a disjoint passthrough constructs fine"() {
        expect:
        ChildEnvAllowlist.of(['JAVA_HOME'], ['GNOMISH_GITHUB_TOKEN']) != null
    }

    def "D6: compose layers base, passthrough, and factory-set — later layers win on a collision"() {
        given: 'a factory environment carrying base, passthrough, and colliding names'
        def factoryEnv = [PATH: '/usr/bin', JAVA_HOME: '/jdk', SHARED: 'from-env']
        def allowlist = ChildEnvAllowlist.over(['JAVA_HOME', 'SHARED'], [], {
            -> factoryEnv
        })

        when:
        def composed = allowlist.compose(['PATH', 'SHARED'], [SHARED: 'factory-set', GNOMISH_X: 'proto'])

        then: 'all three layers are present and the factory-set value won the collision'
        composed == [PATH: '/usr/bin', SHARED: 'factory-set', JAVA_HOME: '/jdk', GNOMISH_X: 'proto']
    }

    def "D6: a name unset in the factory environment is simply omitted"() {
        given:
        def allowlist = ChildEnvAllowlist.over(['MISSING_TOOL_VAR'], [], {
            -> [PATH: '/usr/bin']
        })

        expect:
        allowlist.compose(['PATH', 'MISSING_BASE_VAR'], [:]) == [PATH: '/usr/bin']
    }

    def "D6: values are read live at compose time, never captured at construction"() {
        given: 'a mutable factory-environment source'
        def factoryEnv = [TOOL: 'v1']
        def allowlist = ChildEnvAllowlist.over(['TOOL'], [], { -> factoryEnv })

        when:
        def first = allowlist.compose([], [:])
        factoryEnv.TOOL = 'v2'
        def second = allowlist.compose([], [:])

        then:
        first == [TOOL: 'v1']
        second == [TOOL: 'v2']
    }

    def "NFR-S1: a declared credential name never appears in a composed map, whatever layer carries it"() {
        given: 'a credential name present in the factory environment and even in the factory-set fragment'
        def factoryEnv = [PATH: '/usr/bin', GNOMISH_GITHUB_TOKEN: 'secret']
        def allowlist = ChildEnvAllowlist.over([], ['GNOMISH_GITHUB_TOKEN'], {
            -> factoryEnv
        })

        expect: 'the credential is absent even when named in base or factory-set'
        allowlist.compose([
            'PATH',
            'GNOMISH_GITHUB_TOKEN'
        ], [GNOMISH_GITHUB_TOKEN: 'leak']) == [PATH: '/usr/bin']
    }

    def "none() composes exactly the base-and-factory-set formula with no passthrough"() {
        expect: 'an empty compose is empty — nothing is inherited implicitly'
        ChildEnvAllowlist.none().compose([], [:]) == [:]
        ChildEnvAllowlist.none().compose([], [GNOMISH_X: 'v']) == [GNOMISH_X: 'v']
    }
}
