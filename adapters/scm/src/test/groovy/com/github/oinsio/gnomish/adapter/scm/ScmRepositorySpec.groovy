package com.github.oinsio.gnomish.adapter.scm

import spock.lang.Specification

/**
 * FR4, NFR-S2 of migrate-verify-to-executor-contract: the one reading of a built-in connection's
 * {@code repo} — present, {@code owner/name}, split into the repository's API path.
 */
class ScmRepositorySpec extends Specification {

    def "an owner/name repository is split into its owner, its name and its API path"() {
        when:
        def repository = ScmRepository.of('factory.check.scm-status', [repo: 'acme/widgets'])

        then:
        repository.owner() == 'acme'
        repository.name() == 'widgets'
        repository.apiPath() == '/repos/acme/widgets'
    }

    def "a repository that is absent, blank or no owner/name is refused naming the key: #why"() {
        when:
        ScmRepository.of('factory.check.scm-status', settings)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == message

        where:
        why | settings || message
        'absent' | [:] || 'factory.check.scm-status.repo is required'
        'blank' | [repo: ' '] || 'factory.check.scm-status.repo is required'
        'not a string' | [repo: 7] || 'factory.check.scm-status.repo is required'
        'one segment' | [repo: 'acme'] || "factory.check.scm-status.repo must be 'owner/name', got: 'acme'"
    }

    def "owner/name is exactly one non-empty segment on either side of one slash: '#repo'"() {
        expect:
        ScmRepository.isOwnerName(repo) == ownerName

        where:
        repo || ownerName
        'acme/widgets' || true
        'a/b' || true
        '/widgets' || false
        'acme/' || false
        'acme/widgets/x' || false
        'acme' || false
    }
}
