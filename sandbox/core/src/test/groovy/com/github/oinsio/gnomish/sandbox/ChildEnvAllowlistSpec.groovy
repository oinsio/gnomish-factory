package com.github.oinsio.gnomish.sandbox

import spock.lang.Specification

/**
 * FR9, D6 of add-sandbox-core (task 7.1): the layered positive child-environment
 * allowlist — base ∪ passthrough ∪ factory-set with nothing inherited implicitly,
 * values read live from the factory environment at compose time, a credential
 * name in passthrough refused at construction naming the variable, and declared
 * credential names never present in a composed map.
 */
class ChildEnvAllowlistSpec extends Specification {

    // Scenario: sandbox/execution-environment / A credential passthrough name fails startup
    // Scenario: executor/agent-executor / Credential variable cannot be allowlisted
    def "FR9: a passthrough name declared as a credential is refused at construction, naming the variable"() {
        when:
        ChildEnvAllowlist.of([
            'JAVA_HOME',
            'GNOMISH_GITHUB_TOKEN'
        ], ['GNOMISH_GITHUB_TOKEN'], ChildEnvValues.NONE)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('GNOMISH_GITHUB_TOKEN')
        e.message.contains('factory.sandbox.env-passthrough')
        !e.message.contains('JAVA_HOME')
    }

    def "FR9: every offending passthrough name is named, not just the first"() {
        when:
        ChildEnvAllowlist.of(['TOKEN_A', 'SAFE', 'TOKEN_B'], ['TOKEN_A', 'TOKEN_B'], ChildEnvValues.NONE)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('TOKEN_A')
        e.message.contains('TOKEN_B')
        !e.message.contains('SAFE')
    }

    def "a disjoint passthrough constructs fine"() {
        expect:
        ChildEnvAllowlist.of(['JAVA_HOME'], ['GNOMISH_GITHUB_TOKEN'], ChildEnvValues.NONE) != null
    }

    def "D6: compose layers base, passthrough, and factory-set — later layers win on a collision"() {
        given: 'a factory environment carrying base, passthrough, and colliding names'
        def factoryEnv = [PATH: '/usr/bin', JAVA_HOME: '/jdk', SHARED: 'from-env']
        def allowlist = ChildEnvAllowlist.of(['JAVA_HOME', 'SHARED'], [], valuesOf(factoryEnv))

        when:
        def composed = allowlist.compose(['PATH', 'SHARED'], [SHARED: 'factory-set', GNOMISH_X: 'proto'])

        then: 'all three layers are present and the factory-set value won the collision'
        composed == [PATH: '/usr/bin', SHARED: 'factory-set', JAVA_HOME: '/jdk', GNOMISH_X: 'proto']
    }

    def "D6: a name unset in the factory environment is simply omitted"() {
        given:
        def allowlist = ChildEnvAllowlist.of(['MISSING_TOOL_VAR'], [], valuesOf([PATH: '/usr/bin']))

        expect:
        allowlist.compose(['PATH', 'MISSING_BASE_VAR'], [:]) == [PATH: '/usr/bin']
    }

    def "D6: values are read live at compose time, never captured at construction"() {
        given: 'a mutable factory-environment source'
        def factoryEnv = [TOOL: 'v1']
        def allowlist = ChildEnvAllowlist.of(['TOOL'], [], valuesOf(factoryEnv))

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
        def allowlist = ChildEnvAllowlist.of([], ['GNOMISH_GITHUB_TOKEN'], valuesOf(factoryEnv))

        expect: 'the credential is absent even when named in base or factory-set'
        allowlist.compose([
            'PATH',
            'GNOMISH_GITHUB_TOKEN'
        ], [GNOMISH_GITHUB_TOKEN: 'leak']) == [PATH: '/usr/bin']
    }

    // FR9, D6: a passthrough entry is a name, never a pattern — no glob, prefix or regex expands
    // Scenario: sandbox/execution-environment / Passthrough names are exact
    def "FR9: a passthrough entry matches exactly one variable name, and no pattern is expanded"() {
        given: 'a factory environment with near-miss names beside the listed one, and pattern-shaped entries'
        def factoryEnv = [JAVA_HOME: '/jdk', JAVA_HOME_17: '/jdk17', MY_JAVA_HOME: '/other', TOOL_A: 'a', TOOL_B: 'b',
            'TOOL_*': 'literal']
        def allowlist = ChildEnvAllowlist.of([
            'JAVA_HOME',
            'TOOL_*',
            'TOOL_.*',
            'TOOL_?'
        ], [], valuesOf(factoryEnv))

        expect: 'only the exact names are carried — a pattern-shaped entry matches only a variable spelled exactly so'
        allowlist.compose([], [:]) == [JAVA_HOME: '/jdk', 'TOOL_*': 'literal']
    }

    // Scenario: executor/agent-executor / Tracker token is absent from the round
    def "none() composes exactly the base-and-factory-set formula with no passthrough"() {
        expect: 'an empty compose is empty — nothing is inherited implicitly'
        ChildEnvAllowlist.none().compose([], [:]) == [:]
        ChildEnvAllowlist.none().compose([], [GNOMISH_X: 'v']) == [GNOMISH_X: 'v']
    }

    // FR9, D6: the operator passthrough adds the one variable it names to the base set and the
    //     factory-set layer, and a name that merely shares its prefix stays behind
    // Scenario: executor/agent-executor / Passthrough names match exactly
    def "FR9: a round receives the passthrough variable itself, beside base and factory-set, and no prefix sibling"() {
        given:
        def factoryEnv = [PATH: '/usr/bin', HOME: '/home/op', NPM_TOKEN: 'n', NPM_TOKEN_CI: 'ci', NPM_TOKENS: 'ts',
            UNRELATED: 'u']
        def allowlist = ChildEnvAllowlist.of(['NPM_TOKEN'], [], valuesOf(factoryEnv))

        expect:
        allowlist.compose(['PATH', 'HOME'], [GNOMISH_DECISION_FILE: '/d.json']) ==
        [PATH: '/usr/bin', HOME: '/home/op', NPM_TOKEN: 'n', GNOMISH_DECISION_FILE: '/d.json']
    }

    // NFR-S3 of tighten-spec-contract: none() resolves no value at all — it reads no environment,
    //     so a base name the factory environment would carry stays out
    def "NFR-S3: none() resolves no base, passthrough or factory-set name"() {
        expect:
        ChildEnvAllowlist.none().compose(['PATH', 'HOME'], [:]) == [:]
        ChildEnvAllowlist.none().resolve(['PATH']) == [:]
    }

    // NFR-S3 of tighten-spec-contract: every admitted value is resolved by name through the
    //     allowlist's values — the composition root's secrets port — and nothing else is asked
    def "NFR-S3: compose asks its values for exactly the base and passthrough names"() {
        given:
        def asked = []
        def allowlist = ChildEnvAllowlist.of(['TOOL'], [], { String name ->
            asked << name
            Optional.of("v-${name}".toString())
        } as ChildEnvValues)

        when:
        def composed = allowlist.compose(['PATH'], [GNOMISH_X: 'proto'])

        then:
        asked == ['PATH', 'TOOL']
        composed == [PATH: 'v-PATH', TOOL: 'v-TOOL', GNOMISH_X: 'proto']
    }

    // NFR-S3 of tighten-spec-contract: the agent seam's factory-set variables resolve through the
    //     same values, in the order asked, absent ones omitted and a declared credential never
    def "NFR-S3: resolve returns the present names in order and never a declared credential"() {
        given:
        def allowlist = ChildEnvAllowlist.of([], ['GNOMISH_GITHUB_TOKEN'],
        valuesOf([B: 'b', A: 'a', GNOMISH_GITHUB_TOKEN: 'secret']))

        expect:
        allowlist.resolve([
            'A',
            'MISSING',
            'B',
            'GNOMISH_GITHUB_TOKEN'
        ]) == [A: 'a', B: 'b']
        allowlist.resolve(['A', 'MISSING', 'B']).keySet().toList() == ['A', 'B']
    }

    // FR9; NFR-S3 of tighten-spec-contract: declaring widens the credential set over the same
    //     values and passthrough, and validates the union
    def "declaring adds credential names: the new ones are excluded, the old ones stay excluded, passthrough is kept"() {
        given:
        def base = ChildEnvAllowlist.of(['TOOL'], ['OLD_TOKEN'],
        valuesOf([TOOL: 't', OLD_TOKEN: 'o', NEW_TOKEN: 'n', PATH: '/usr/bin']))

        when:
        def widened = base.declaring(['NEW_TOKEN'])

        then:
        widened.compose([
            'PATH',
            'OLD_TOKEN',
            'NEW_TOKEN'
        ], [:]) == [PATH: '/usr/bin', TOOL: 't']
        base.compose(['NEW_TOKEN'], [:]) == [NEW_TOKEN: 'n', TOOL: 't']
    }

    def "FR9: declaring a credential the passthrough names is refused, naming the variable"() {
        when:
        ChildEnvAllowlist.of(['TOOL', 'NPM_TOKEN'], [], ChildEnvValues.NONE).declaring(['NPM_TOKEN'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('NPM_TOKEN')
        !e.message.contains('TOOL')
    }

    /** The factory's values over a plain map, read live at every lookup. */
    private static ChildEnvValues valuesOf(Map<String, String> env) {
        { String name -> Optional.ofNullable(env[name]) } as ChildEnvValues
    }
}
