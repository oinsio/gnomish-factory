package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import spock.lang.Specification

/**
 * FR1, NFR-S1 of add-doctor-command (design D4): {@link AgentCredentials} reports which AI-seam
 * credential names resolve through a round's child-environment allowlist — names only, never a
 * value — by asking the same question an agent round asks ({@link AgentAiSeam#variables}).
 */
class AgentCredentialsSpec extends Specification {

    def "FR1: the credential subset is the provider auth token and the CLI's own two credentials, in seam order"() {
        expect:
        AgentAiSeam.CREDENTIAL_NAMES == [
            'ANTHROPIC_AUTH_TOKEN',
            'CLAUDE_CODE_OAUTH_TOKEN',
            'ANTHROPIC_API_KEY'
        ]

        and: 'every credential name is a seam name, so the round carries what the report names'
        AgentAiSeam.NAMES.containsAll(AgentAiSeam.CREDENTIAL_NAMES)
    }

    // FR1: iterates CREDENTIAL_NAMES rather than a hand-listed subset, so a credential added to the
    //     seam is covered by construction.
    def "FR1: credential #name is reported when it resolves and not when it is absent"() {
        expect:
        presentOver([(name): 'secret-value', PATH: '/usr/bin']) == [name]
        presentOver([PATH: '/usr/bin']) == []

        where:
        name << AgentAiSeam.CREDENTIAL_NAMES
    }

    def "FR1: every present credential is reported in seam order, and the non-secret seam names are not"() {
        given: 'every seam variable and unrelated noise, in an order unlike the seam'
        def factoryEnv = [
            ANTHROPIC_API_KEY: 'k',
            ANTHROPIC_MODEL: 'm',
            CLAUDE_CODE_OAUTH_TOKEN: 'o',
            ANTHROPIC_BASE_URL: 'http://localhost:11434',
            ANTHROPIC_AUTH_TOKEN: 't',
            AWS_SECRET_ACCESS_KEY: 'never',
        ]

        expect:
        presentOver(factoryEnv) == [
            'ANTHROPIC_AUTH_TOKEN',
            'CLAUDE_CODE_OAUTH_TOKEN',
            'ANTHROPIC_API_KEY'
        ]
    }

    def "FR1: a base URL and a model alone are no credential"() {
        expect:
        presentOver([ANTHROPIC_BASE_URL: 'http://localhost:11434', ANTHROPIC_MODEL: 'm']) == []
    }

    // FR1: the allowlist scrubs a declared credential from what an agent round carries; the report
    //     follows the round, so a scrubbed name is absent here too.
    def "FR1: a credential name the allowlist scrubs is not reported, as the round would not carry it"() {
        given:
        def childEnv = ChildEnvAllowlist.of([], ['ANTHROPIC_API_KEY'],
        TestChildEnvironment.valuesOf([ANTHROPIC_API_KEY: 'k', CLAUDE_CODE_OAUTH_TOKEN: 'o']))

        expect:
        AgentCredentials.presentNames(childEnv) == ['CLAUDE_CODE_OAUTH_TOKEN']
        AgentAiSeam.variables(childEnv).keySet() == ['CLAUDE_CODE_OAUTH_TOKEN'] as Set
    }

    def "NFR-S1: no resolved value appears in the result, only names"() {
        given:
        def values = [
            ANTHROPIC_AUTH_TOKEN: 'sk-auth-VALUE-1',
            CLAUDE_CODE_OAUTH_TOKEN: 'sk-oauth-VALUE-2',
            ANTHROPIC_API_KEY: 'sk-api-VALUE-3',
        ]

        when:
        def result = presentOver(values)

        then: 'the result is exactly the names'
        result == values.keySet().toList()

        and: 'no value is in it, nor in its rendering'
        values.values().every { value ->
            !result.contains(value) && !result.toString().contains(value)
        }
    }

    /** The present credential names over a planted factory environment. */
    private static List<String> presentOver(Map<String, String> factoryEnv) {
        AgentCredentials.presentNames(ChildEnvAllowlist.of([], [], TestChildEnvironment.valuesOf(factoryEnv)))
    }

    def "FR1: the names an operator may set are the credential subset, in seam order"() {
        expect:
        AgentCredentials.names() == AgentAiSeam.CREDENTIAL_NAMES
    }
}
