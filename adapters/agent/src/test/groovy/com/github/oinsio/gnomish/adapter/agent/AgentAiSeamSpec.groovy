package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import spock.lang.Specification

/**
 * FR9, D6 of add-sandbox-core: the AI base-url/auth-token seam — with the
 * layered allowlist nothing is inherited, so the agent adapters explicitly set
 * the three seam variables from the factory environment (the same three the
 * Ollama E2E path uses, D11 of add-agent-executor), omitting unset names.
 *
 * <p>FR5 of fix-operator-blockers: the seam also carries the agent CLI's own credentials,
 * {@code CLAUDE_CODE_OAUTH_TOKEN} and {@code ANTHROPIC_API_KEY}.
 *
 * <p>NFR-S3 of tighten-spec-contract: the values are resolved through the round's allowlist — the
 * secrets port, in production — never read from the process environment.
 */
class AgentAiSeamSpec extends Specification {

    def "the seam selects exactly the set seam variables, in order, omitting unset names"() {
        given: 'a factory environment with two of the three seam variables and unrelated noise'
        def factoryEnv = [
            ANTHROPIC_AUTH_TOKEN: 'tok',
            ANTHROPIC_BASE_URL: 'http://localhost:11434',
            AWS_SECRET_ACCESS_KEY: 'never',
        ]

        expect: 'only the present seam names are selected, base-url first'
        seamOver(factoryEnv) == [
            ANTHROPIC_BASE_URL: 'http://localhost:11434',
            ANTHROPIC_AUTH_TOKEN: 'tok',
        ]
    }

    // FR5: iterates NAMES rather than a hand-listed subset, so a name added to the seam is covered
    //     by construction and a name dropped from it fails the pinned-set row below.
    // Scenario: executor/agent-executor / An absent seam variable is omitted
    def "FR5: seam variable #name is selected with its live value when set and omitted when unset"() {
        expect: 'present: selected with the factory-environment value'
        seamOver([(name): "value-of-${name}".toString(), PATH: '/usr/bin']) ==
        [(name): "value-of-${name}".toString()]

        and: 'absent: omitted, not set to an empty value'
        seamOver([PATH: '/usr/bin']).isEmpty()

        where:
        name << AgentAiSeam.NAMES
    }

    def "FR5: the seam carries the agent CLI's own credentials beside the provider variables"() {
        expect:
        AgentAiSeam.NAMES == [
            'ANTHROPIC_BASE_URL',
            'ANTHROPIC_AUTH_TOKEN',
            'ANTHROPIC_MODEL',
            'CLAUDE_CODE_OAUTH_TOKEN',
            'ANTHROPIC_API_KEY',
        ]
    }

    def "an environment without seam variables yields an empty fragment"() {
        expect:
        seamOver([PATH: '/usr/bin']) == [:]
    }

    // NFR-S3 of tighten-spec-contract: the seam asks the allowlist's values for its five names and
    //     nothing else, so the values come from the source the root binds to the secrets port
    def "NFR-S3: the seam resolves its names through the allowlist's values, and asks for nothing else"() {
        given:
        def asked = []
        def childEnv = ChildEnvAllowlist.of([], [], { String name ->
            asked << name
            Optional.of('v')
        } as com.github.oinsio.gnomish.sandbox.ChildEnvValues)

        expect:
        AgentAiSeam.variables(childEnv).keySet().toList() == AgentAiSeam.NAMES
        asked == AgentAiSeam.NAMES
    }

    /** The seam over a planted factory environment. */
    private static Map<String, String> seamOver(Map<String, String> factoryEnv) {
        AgentAiSeam.variables(ChildEnvAllowlist.of([], [], TestChildEnvironment.valuesOf(factoryEnv)))
    }
}
