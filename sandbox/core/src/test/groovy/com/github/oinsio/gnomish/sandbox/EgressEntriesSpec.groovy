package com.github.oinsio.gnomish.sandbox

import spock.lang.Specification

/**
 * The one grammar of an egress allowlist entry (FR7 of add-sandbox-core; FR9 of
 * define-executor-contract): the guard renders the operator's allowlist through it and the law
 * loader judges an executor declaration's egress hosts with it.
 */
class EgressEntriesSpec extends Specification {

    def "FR7, FR9: '#entry' is an entry: #accepted"() {
        expect:
        EgressEntries.isEntry(entry) == accepted

        where:
        entry || accepted
        'api.acme.example' || true
        ' API.Acme.Example ' || true
        '*.acme.example' || true
        '10.0.0.1' || true
        '::1' || true
        'https://api.acme.com' || false
        'a b' || false
        '' || false
        '"x"' || false
    }

    def "FR7: an entry is normalized by stripping and folding to lower case"() {
        expect:
        EgressEntries.normalized(' API.Acme.Example ') == 'api.acme.example'
    }
}
