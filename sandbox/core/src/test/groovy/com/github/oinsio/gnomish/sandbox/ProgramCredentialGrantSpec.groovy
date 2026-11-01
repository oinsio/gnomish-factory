package com.github.oinsio.gnomish.sandbox

import spock.lang.Specification

/**
 * NFR-S1 of define-executor-contract (design D10, ADR 0017): a {@code program} executor's
 * credentials are its child's own resolved layer, judged against the allowlist's two shared layers
 * and resolved through the allowlist's own value source — and, granted, they survive the
 * composition every child goes through, because they are never a declared name.
 */
class ProgramCredentialGrantSpec extends Specification {

    static final Map<String, String> SECRETS = [ACME_API_KEY: 'acme-secret', GNOMISH_GITHUB_TOKEN: 'gh', OPERATOR_VAR: 'op']

    static final ChildEnvValues VALUES = { String name ->
        Optional.ofNullable(SECRETS[name])
    } as ChildEnvValues

    final ChildEnvAllowlist allowlist = ChildEnvAllowlist.of(['OPERATOR_VAR'], ['GNOMISH_GITHUB_TOKEN'], VALUES)

    def "NFR-S1: '#name' stands #standing"() {
        expect:
        allowlist.programGrant().standing(name) == standing

        where:
        name || standing
        'ACME_API_KEY' || CredentialGrantStanding.GRANTABLE
        'OPERATOR_VAR' || CredentialGrantStanding.PASSED_THROUGH
        'GNOMISH_GITHUB_TOKEN' || CredentialGrantStanding.WITHHELD
        'ABSENT_KEY' || CredentialGrantStanding.NO_VALUE
    }

    def "NFR-S1: a widened allowlist's grant withholds the names it declares besides its own"() {
        expect:
        allowlist.declaring(['ACME_API_KEY']).programGrant().standing('ACME_API_KEY') == CredentialGrantStanding.WITHHELD
    }

    def "NFR-S1: the grant resolves each granted name through the allowlist's source, in order, and survives composition"() {
        when:
        def granted = allowlist.programGrant().grant(['ACME_API_KEY'])

        then: 'the value from the source'
        granted == [ACME_API_KEY: 'acme-secret']

        and: 'the program exec composes it as its factory-set layer, a sibling composes none of it'
        allowlist.compose([], granted + [GNOMISH_RESULT_FILE: '/r']).subMap(['ACME_API_KEY']) == [ACME_API_KEY: 'acme-secret']
        !allowlist.compose([], [:]).containsKey('ACME_API_KEY')
    }

    def "NFR-S1: an ungrantable name is refused by name and standing, granting nothing"() {
        when:
        allowlist.programGrant().grant(['ACME_API_KEY', name])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "credential '${name}' cannot be granted to a program executor: ${standing}".toString()

        where:
        name || standing
        'OPERATOR_VAR' || CredentialGrantStanding.PASSED_THROUGH
        'GNOMISH_GITHUB_TOKEN' || CredentialGrantStanding.WITHHELD
        'ABSENT_KEY' || CredentialGrantStanding.NO_VALUE
    }

    def "NFR-S1: an empty grant is empty and unmodifiable"() {
        when:
        def granted = allowlist.programGrant().grant([])
        granted.put('X', 'y')

        then:
        thrown(UnsupportedOperationException)
    }
}
