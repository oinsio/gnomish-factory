package com.github.oinsio.gnomish.adapter.check.github

import spock.lang.Specification

/**
 * FR4, FR5, design D12 of add-plugin-architecture: the github check provider grades its own
 * {@code factory.check.github} operator subsection. Every problem is returned as located
 * {@code ConfigError} data — never thrown — so the composition root can aggregate one provider's
 * complaints with every other provider's into a single startup report.
 */
class GithubCheckSubsectionValidatorSpec extends Specification {

    private static final String FILE = 'application.yaml'
    private static final String WHERE = 'factory.check.github'

    private final validator = new GithubCheckSubsectionValidator()

    private def validate(Map<String, Object> subsection) {
        validator.validate(FILE, WHERE, subsection)
    }

    // FR4: a fully declared connection is valid and reports nothing.
    def "a complete connection subsection is valid"() {
        expect:
        validate([('api-url'): 'https://api.github.com', repo: 'acme/widgets']).isEmpty()
    }

    // FR5, task 2.7: half a connection is a configuration mistake, never a silently disabled
    //     provider — and it is a LOCATED error naming the missing key, not a thrown exception.
    def "a subsection declaring only #present is a located error naming the missing key"() {
        when:
        def errors = validate(subsection)

        then:
        errors.size() == 1
        errors[0].file() == FILE
        errors[0].where() == WHERE + '.' + missing
        errors[0].message().contains('both api-url and repo')

        where:
        present | missing | subsection
        'api-url' | 'repo' | [('api-url'): 'https://api.github.com']
        'repo' | 'api-url' | [repo: 'acme/widgets']
    }

    // FR5: an empty subsection reports BOTH missing keys at once rather than stopping at the first
    //     — the aggregation contract is what makes one startup report enough to fix everything.
    def "an empty subsection reports both missing connection keys"() {
        when:
        def errors = validate([:])

        then:
        errors*.where() as Set == [
            WHERE + '.api-url',
            WHERE + '.repo'
        ] as Set
    }

    // FR5: a blank value is not a value — a key present but empty is the same mistake as omitting it.
    def "a blank connection value is treated as missing"() {
        expect:
        validate([('api-url'): '  ', repo: 'acme/widgets']).size() == 1
    }

    // FR5: the coordinate shape is the provider's own rule, graded at the load seam rather than
    //     surfacing later as a mid-take adapter failure.
    def "a repo that is not owner/name is a located error"() {
        when:
        def errors = validate([('api-url'): 'https://api.github.com', repo: repo])

        then:
        errors.size() == 1
        errors[0].where() == WHERE + '.repo'
        errors[0].message().contains(repo)

        where:
        repo << [
            'not-a-repo-ref',
            '/widgets',
            'acme/',
            'acme/widgets/extra'
        ]
    }

    // NFR-S1: the token is resolved through the SecretsProvider only; a credential-shaped key in
    //     operator configuration is refused however it is spelled.
    def "a credential-shaped key '#key' is refused"() {
        when:
        def errors = validate([('api-url'): 'https://api.github.com', repo: 'acme/widgets', (key): 'secret'])

        then:
        errors.size() == 1
        errors[0].where() == WHERE + '.' + key
        errors[0].message().contains('GNOMISH_GITHUB_ACTIONS_TOKEN')

        where:
        key << [
            'token',
            'api-token',
            'apiToken',
            'access_token'
        ]
    }
}
