package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import spock.lang.Specification

/**
 * GithubDesignatorsValidator (github-tracker spec, "Designator rule without
 * exactly one capture group is a load error"): the {@code
 * tracker.github.designators} map is graded on the adapter's own config seam,
 * located exactly like {@code labels}, and aggregated with the rest.
 *
 * Implements FR3 of add-base-ref-resolution.
 */
class GithubDesignatorsValidatorSpec extends Specification {

    private static final String FILE = 'config.yaml'
    private static final String WHERE = 'tracker.github.designators'

    private static List<ConfigError> validate(Object designators) {
        GithubDesignatorsValidator.validate(FILE, WHERE, designators)
    }

    // FR3: a well-formed map of kind to one-group regex loads clean
    def "a rule with exactly one capture group is accepted"() {
        expect:
        validate([base: 'base:(.+)', type: '(?:gnomish:)?type:(.+)']).isEmpty()
    }

    // FR3: exactly one capture group -- none and two are both located errors naming the kind
    def "a rule without exactly one capture group is a located error: #why"() {
        when:
        def errors = validate([base: rule])

        then:
        errors.size() == 1
        errors[0].file() == FILE
        errors[0].where() == 'tracker.github.designators.base'
        errors[0].message().contains('exactly one capture group')

        where:
        why | rule
        'no group' | 'base:.+'
        'two groups' | '(base):(.+)'
    }

    // FR3: a rule that does not compile is a located error naming the kind, not an adapter crash
    def "a rule that does not compile is a located error"() {
        when:
        def errors = validate([base: 'base:('])

        then:
        errors.size() == 1
        errors[0].where() == 'tracker.github.designators.base'
        errors[0].message().contains('not a valid regular expression')
    }

    // FR3: the value must be a regular expression -- anything else is located at the kind
    def "a non-string or blank rule is a located error: #why"() {
        when:
        def errors = validate([base: rule])

        then:
        errors.size() == 1
        errors[0].where() == 'tracker.github.designators.base'

        where:
        why | rule
        'a number' | 42
        'a map' | [pattern: 'base:(.+)']
        'blank' | '   '
    }

    // FR3: the map itself must be a map
    def "a designators value that is not a map is one located error on the map"() {
        when:
        def errors = validate('base:(.+)')

        then:
        errors == [
            new ConfigError(FILE, WHERE, 'must be an object mapping designator kinds to regular expressions')
        ]
    }

    // FR3: kinds are open names, but a blank one names nothing
    def "a blank kind name is a located error"() {
        when:
        def errors = validate(['  ': 'base:(.+)'])

        then:
        errors.size() == 1
        errors[0].where() == WHERE
        errors[0].message().contains('must not be blank')
    }

    // UX1: every problem in the map is reported in one pass, in kind order
    def "problems aggregate across kinds"() {
        when:
        def errors = validate([base: 'base:.+', type: 'type:('])

        then:
        errors*.where() == [
            'tracker.github.designators.base',
            'tracker.github.designators.type'
        ]
    }
}
