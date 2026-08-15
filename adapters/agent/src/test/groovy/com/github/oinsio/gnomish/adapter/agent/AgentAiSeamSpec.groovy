package com.github.oinsio.gnomish.adapter.agent

import spock.lang.Specification

/**
 * FR9, D6 of add-sandbox-core: the AI base-url/auth-token seam — with the
 * layered allowlist nothing is inherited, so the agent adapters explicitly set
 * the three seam variables from the factory environment (the same three the
 * Ollama E2E path uses, D11 of add-agent-executor), omitting unset names.
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
        AgentAiSeam.fromEnvironment(factoryEnv) == [
            ANTHROPIC_BASE_URL: 'http://localhost:11434',
            ANTHROPIC_AUTH_TOKEN: 'tok',
        ]
    }

    def "an environment without seam variables yields an empty fragment"() {
        expect:
        AgentAiSeam.fromEnvironment([PATH: '/usr/bin']) == [:]
    }

    def "the production selection reads the real factory environment"() {
        expect: 'consistent with a direct selection over System.getenv()'
        AgentAiSeam.fromFactoryEnvironment() == AgentAiSeam.fromEnvironment(System.getenv())
    }
}
