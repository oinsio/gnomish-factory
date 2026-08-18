package com.github.oinsio.gnomish.adapter.check.github

import spock.lang.Specification

/**
 * FR5, FR6 of add-plugin-architecture: the github check provider grades an {@code external} check's
 * own {@code params} at the load seam. This provider takes its whole target from the check's
 * engine-common {@code checkId}, so it defines no params — and says so, located, rather than
 * ignoring a params block written for a different provider.
 */
class GithubCheckParamsValidatorSpec extends Specification {

    private static final String FILE = 'stages/build/stage.yaml'
    private static final String WHERE = 'verify[0].params'

    private final validator = new GithubCheckParamsValidator()

    // FR6: the ordinary case — a github check carries no params and validates clean.
    def "no params is valid"() {
        expect:
        validator.validate(FILE, WHERE, [:]).isEmpty()
    }

    // FR5: a param this provider does not define is a located error identifying the check and the
    //     offending key — the check-provider-model scenario "Provider validates its own params".
    def "an unknown param is a located error naming the key and the provider"() {
        when:
        def errors = validator.validate(FILE, WHERE, [('pass_when'): [jsonPath: '$.status']])

        then:
        errors.size() == 1
        errors[0].file() == FILE
        errors[0].where() == WHERE + '.pass_when'
        errors[0].message().contains('pass_when')
        errors[0].message().contains('github')
    }

    // FR5: every offending key is reported, in a stable order, so one load pass names them all.
    def "every unknown param is reported in key order"() {
        when:
        def errors = validator.validate(FILE, WHERE, [zeta: 1, alpha: 2])

        then:
        errors*.where() == [
            WHERE + '.alpha',
            WHERE + '.zeta'
        ]
    }
}
