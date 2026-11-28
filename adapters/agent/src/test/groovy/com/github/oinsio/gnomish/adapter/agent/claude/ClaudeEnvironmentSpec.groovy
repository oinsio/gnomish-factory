package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ChildEnvValues
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import spock.lang.Specification

/**
 * NFR-S1, D8 of extract-agent-round-runner: the credential variables the Claude describe declares
 * reach a round only as resolved through the run's child-environment allowlist — the secrets port, in
 * production (NFR-S3, finding F107 of tighten-spec-contract) — present names only; and the egress
 * host the describe declares is the host of the base URL that allowlist resolves.
 *
 * <p>FR9, D6 of add-sandbox-core; FR5 of fix-operator-blockers (the CLI's own two credentials).
 */
class ClaudeEnvironmentSpec extends Specification {

    def "FR5: the declared credentials are the provider variables and the CLI's own two, in round order"() {
        expect:
        ClaudeEnvironment.CREDENTIALS == [
            'ANTHROPIC_BASE_URL',
            'ANTHROPIC_AUTH_TOKEN',
            'ANTHROPIC_MODEL',
            'CLAUDE_CODE_OAUTH_TOKEN',
            'ANTHROPIC_API_KEY',
        ]
        ClaudeEnvironment.SECRETS == [
            'ANTHROPIC_AUTH_TOKEN',
            'CLAUDE_CODE_OAUTH_TOKEN',
            'ANTHROPIC_API_KEY'
        ]
    }

    def "the round's variables are exactly the present credentials, in order, omitting unset names"() {
        expect:
        variablesOver([
            ANTHROPIC_AUTH_TOKEN: 'tok',
            ANTHROPIC_BASE_URL: 'http://localhost:11434',
            AWS_SECRET_ACCESS_KEY: 'never',
        ]) == [ANTHROPIC_BASE_URL: 'http://localhost:11434', ANTHROPIC_AUTH_TOKEN: 'tok']
        variablesOver([PATH: '/usr/bin']) == [:]
    }

    // FR5: iterates CREDENTIALS rather than a hand-listed subset, so a declared name is covered by
    //     construction.
    // Scenario: executor/agent-executor / An absent seam variable is omitted
    def "FR5: credential #name is carried with its live value when set and omitted when unset"() {
        expect:
        variablesOver([(name): "value-of-${name}".toString(), PATH: '/usr/bin']) ==
        [(name): "value-of-${name}".toString()]
        variablesOver([PATH: '/usr/bin']).isEmpty()

        where:
        name << ClaudeEnvironment.CREDENTIALS
    }

    // NFR-S3 of tighten-spec-contract: the allowlist's values are asked for the declared names and
    //     nothing else
    def "NFR-S1: the credentials resolve through the allowlist's values, which are asked for nothing else"() {
        given:
        def asked = []
        def childEnv = ChildEnvAllowlist.of([], [], { String name ->
            asked << name
            Optional.of('v')
        } as ChildEnvValues)

        expect:
        ClaudeEnvironment.variables(childEnv).keySet().toList() == ClaudeEnvironment.CREDENTIALS
        asked == ClaudeEnvironment.CREDENTIALS
    }

    def "NFR-S1: a credential the allowlist withholds is not carried"() {
        given:
        def childEnv = ChildEnvAllowlist.of([], ['ANTHROPIC_API_KEY'],
        TestChildEnvironment.valuesOf([ANTHROPIC_API_KEY: 'k', CLAUDE_CODE_OAUTH_TOKEN: 'o']))

        expect:
        ClaudeEnvironment.variables(childEnv) == [CLAUDE_CODE_OAUTH_TOKEN: 'o']
    }

    def "FR7: the egress host is the provider's own when no base URL resolves"() {
        expect:
        ClaudeEnvironment.egressHost(over([:])) == 'api.anthropic.com'
    }

    def "FR7: the egress host is the host of the configured base URL #url"() {
        expect:
        ClaudeEnvironment.egressHost(over([ANTHROPIC_BASE_URL: url])) == host

        where:
        url || host
        'http://localhost:11434' || 'localhost'
        'https://gateway.example.com/v1/' || 'gateway.example.com'
        ' https://proxy.internal ' || 'proxy.internal'
    }

    def "FR7: a base URL that names no host (#url) is refused, naming the variable and not the value"() {
        when:
        ClaudeEnvironment.egressHost(over([ANTHROPIC_BASE_URL: url]))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.startsWith('ANTHROPIC_BASE_URL names no host')
        !refused.message.contains(url.strip())

        where:
        url << [
            'not a url',
            'gateway-only',
            'file:///tmp/x'
        ]
    }

    private static Map<String, String> variablesOver(Map<String, String> values) {
        ClaudeEnvironment.variables(over(values))
    }

    private static ChildEnvAllowlist over(Map<String, String> values) {
        ChildEnvAllowlist.of([], [], TestChildEnvironment.valuesOf(values))
    }
}
