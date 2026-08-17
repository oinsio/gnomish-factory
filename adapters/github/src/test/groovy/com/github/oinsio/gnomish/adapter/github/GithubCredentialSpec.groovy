package com.github.oinsio.gnomish.adapter.github

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import spock.lang.Specification

/**
 * {@link GithubCredential}: the {@code credential} connection key both github providers read, which
 * is what lets a named connection profile rename a vendor's credential (FR16, FR17, design D8/D11 of
 * add-plugin-architecture).
 *
 * <p>The default matters as much as the override: a subsection that never heard of profiles must
 * keep resolving the historical constant, or every existing installation would break on the day
 * profiles landed.
 */
class GithubCredentialSpec extends Specification {

    private static final String DEFAULT_NAME = 'GNOMISH_GITHUB_TOKEN'
    private static final String FILE = 'application.yaml'
    private static final String WHERE = 'factory.check.github'

    // FR16: a connection that names its credential is honoured — this is the whole point of the key.
    def "a declared credential name is used"() {
        expect:
        GithubCredential.nameOr([credential: 'GNOMISH_GH_MAIN'], DEFAULT_NAME) == 'GNOMISH_GH_MAIN'
    }

    // FR16: absent, blank or non-string falls back to the provider's constant, so an inline
    //     subsection predating profiles resolves exactly the variable it always did.
    def "an absent or unusable credential name falls back to the provider's default"() {
        expect:
        GithubCredential.nameOr(subsection, DEFAULT_NAME) == DEFAULT_NAME

        where:
        subsection << [
            [:],
            [('api-url'): 'https://api.github.com'],
            [credential: '   '],
            [credential: 42]
        ]
    }

    // NFR-S1: a blank or non-string name would resolve nothing and fail only at first poll; it is a
    //     located configuration error instead.
    def "a present but unusable credential key is a located error"() {
        expect:
        GithubCredential.validateName([credential: value], FILE, WHERE) == [
            new ConfigError(FILE, 'factory.check.github.credential',
            "'credential' must be a non-blank credential environment variable name")
        ]

        where:
        value << ['', '  ', 42, [:]]
    }

    // FR16: absent is legal (the default applies) and a well-formed name is legal; neither is graded.
    def "an absent or well-formed credential key reports nothing"() {
        expect:
        GithubCredential.validateName([:], FILE, WHERE).isEmpty()
        GithubCredential.validateName([credential: 'GNOMISH_GH_MAIN'], FILE, WHERE).isEmpty()
    }
}
