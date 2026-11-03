package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * NFR-S1 of define-executor-contract (design D10): the granted credential names of each described
 * program executor — names only, by law name, copied and unmodifiable.
 */
class ExecutorCredentialNamesSpec extends Specification {

    def "NFR-S1: the names granted to an executor, empty for one with none or not described"() {
        given:
        def names = new ExecutorCredentialNames([acme: ['ACME_API_KEY', 'ACME_ORG'], lint: []])

        expect:
        names.of('acme') == ['ACME_API_KEY', 'ACME_ORG']
        names.of('lint') == []
        names.of('judge') == []
        ExecutorCredentialNames.none().byExecutor().isEmpty()
    }

    def "NFR-S1: the map and its lists are copies the caller cannot change"() {
        given:
        def list = ['ACME_API_KEY']
        def map = [acme: list]
        def names = new ExecutorCredentialNames(map)

        when:
        list << 'SNEAKED'
        map.lint = ['X']

        then:
        names.of('acme') == ['ACME_API_KEY']
        names.byExecutor().keySet() == ['acme'] as Set

        when:
        names.byExecutor().put('x', [])

        then:
        thrown(UnsupportedOperationException)

        when:
        names.of('acme').add('Y')

        then:
        thrown(UnsupportedOperationException)
    }
}
