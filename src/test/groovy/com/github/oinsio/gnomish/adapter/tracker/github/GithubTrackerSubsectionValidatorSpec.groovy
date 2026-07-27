package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import spock.lang.Specification

/**
 * GithubTrackerSubsectionValidator (FR17 of add-tracker-port, NFR-S1 of
 * add-tracker-port, design D5, D15): validates the {@code tracker.github}
 * subsection content handed by {@link
 * com.github.oinsio.gnomish.adapter.pipeline.TrackerSeamValidator} — mandatory
 * {@code api-url}, a present {@code repo}, optional per-key {@code labels}
 * with hex color validation, and rejection of any credential-shaped key
 * (NFR-S1: the token comes only from {@code GNOMISH_GITHUB_TOKEN}, never yaml).
 *
 * Implements FR17, NFR-S1 of add-tracker-port.
 */
class GithubTrackerSubsectionValidatorSpec extends Specification {

    private static final String FILE = 'config.yaml'
    private static final String WHERE = 'tracker.github'

    private final GithubTrackerSubsectionValidator validator = new GithubTrackerSubsectionValidator()

    def "a fully valid subsection reports no errors"() {
        given:
        def subsection = [
            'api-url': 'https://api.github.com',
            repo     : 'acme/widgets',
            labels   : [
                ready: [name: 'gnomish:ready', color: '2ea44f']
            ]
        ]

        expect:
        validator.validate(FILE, WHERE, subsection) == []
    }

    def "a minimal valid subsection with no labels reports no errors"() {
        given:
        def subsection = ['api-url': 'https://api.github.com', repo: 'acme/widgets']

        expect:
        validator.validate(FILE, WHERE, subsection) == []
    }

    def "missing api-url is a located load error, no built-in default applied"() {
        given:
        def subsection = [repo: 'acme/widgets']

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.github.api-url', "missing required key 'api-url'")
        ]
    }

    def "a blank api-url is a located error"() {
        given:
        def subsection = ['api-url': '   ', repo: 'acme/widgets']

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.github.api-url', "missing required key 'api-url'")
        ]
    }

    def "missing repo is a located error"() {
        given:
        def subsection = ['api-url': 'https://api.github.com']

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.github.repo', "missing required key 'repo'")
        ]
    }

    def "missing api-url and repo both aggregate as located errors"() {
        given:
        def subsection = [:]

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors as Set == [
            new ConfigError(FILE, 'tracker.github.api-url', "missing required key 'api-url'"),
            new ConfigError(FILE, 'tracker.github.repo', "missing required key 'repo'")
        ] as Set
    }

    def "a token-shaped key inside the subsection is rejected: the token comes only from the env"() {
        given:
        def subsection = [
            'api-url': 'https://api.github.com',
            repo     : 'acme/widgets',
            (tokenKey): 'ghp_secret'
        ]

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors == [
            new ConfigError(FILE, "tracker.github.$tokenKey",
            "'$tokenKey' must not appear in config.yaml; GNOMISH_GITHUB_TOKEN is read from the environment only")
        ]

        where:
        tokenKey << [
            'token',
            'api-token',
            'apiToken',
            'access-token'
        ]
    }

    def "a partial labels map is valid: unspecified label keys fall back to code defaults"() {
        given:
        def subsection = [
            'api-url': 'https://api.github.com',
            repo     : 'acme/widgets',
            labels   : [
                working: [name: 'gnomish:working', color: '1f6feb']
            ]
        ]

        expect:
        validator.validate(FILE, WHERE, subsection) == []
    }

    def "a label entry missing name is a located error"() {
        given:
        def subsection = [
            'api-url': 'https://api.github.com',
            repo     : 'acme/widgets',
            labels   : [ready: [color: '2ea44f']]
        ]

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.github.labels.ready.name', "missing required key 'name'")
        ]
    }

    def "a label entry missing color is a located error"() {
        given:
        def subsection = [
            'api-url': 'https://api.github.com',
            repo     : 'acme/widgets',
            labels   : [ready: [name: 'gnomish:ready']]
        ]

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.github.labels.ready.color', "missing required key 'color'")
        ]
    }

    def "a malformed hex color is a located error: #color"() {
        given:
        def subsection = [
            'api-url': 'https://api.github.com',
            repo     : 'acme/widgets',
            labels   : [ready: [name: 'gnomish:ready', color: color]]
        ]

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.github.labels.ready.color',
            "'$color' is not a valid 6-digit hex color (e.g. '2ea44f', no leading '#')")
        ]

        where:
        color << [
            '#2ea44f',
            '2ea44',
            'gggggg',
            'red',
            '2ea44f1'
        ]
    }

    def "an unknown label key is a located error"() {
        given:
        def subsection = [
            'api-url': 'https://api.github.com',
            repo     : 'acme/widgets',
            labels   : [bogus: [name: 'x', color: '2ea44f']]
        ]

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.github.labels.bogus',
            "unknown label key 'bogus'; expected one of ready, working, needs-human, delivered")
        ]
    }

    def "a labels entry that is not an object is a located error"() {
        given:
        def subsection = [
            'api-url': 'https://api.github.com',
            repo     : 'acme/widgets',
            labels   : [ready: 'gnomish:ready']
        ]

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.github.labels.ready', "must be an object with 'name' and 'color'")
        ]
    }

    def "a labels value that is not an object at all is a located error"() {
        given:
        def subsection = [
            'api-url': 'https://api.github.com',
            repo     : 'acme/widgets',
            labels   : 'not-a-map'
        ]

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.github.labels', 'must be an object mapping label keys to {name, color}')
        ]
    }

    def "multiple problems across api-url, repo, and labels all aggregate in one pass"() {
        given:
        def subsection = [
            labels: [
                ready : [name: 'gnomish:ready', color: 'nothex'],
                bogus2: [name: 'x', color: '2ea44f']
            ]
        ]

        when:
        def errors = validator.validate(FILE, WHERE, subsection)

        then:
        errors as Set == [
            new ConfigError(FILE, 'tracker.github.api-url', "missing required key 'api-url'"),
            new ConfigError(FILE, 'tracker.github.repo', "missing required key 'repo'"),
            new ConfigError(FILE, 'tracker.github.labels.ready.color',
            "'nothex' is not a valid 6-digit hex color (e.g. '2ea44f', no leading '#')"),
            new ConfigError(FILE, 'tracker.github.labels.bogus2',
            "unknown label key 'bogus2'; expected one of ready, working, needs-human, delivered")
        ] as Set
    }
}
