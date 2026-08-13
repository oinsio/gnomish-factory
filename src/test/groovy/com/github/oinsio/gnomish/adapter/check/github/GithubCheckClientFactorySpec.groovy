package com.github.oinsio.gnomish.adapter.check.github

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * FR26 of add-sandbox-core; FR8, NFR-S1 of add-external-check-github-actions: the factory builds
 * the GitHub Actions external-check client from factory config, resolving the token by name
 * through the {@code SecretsProvider} — a missing or blank secret fails closed at wiring time
 * naming the secret; the pin contribution is exactly the {@code checkId} workflow file.
 */
class GithubCheckClientFactorySpec extends Specification {

    private static SecretsProvider providing(Map<String, String> secrets) {
        { name -> Optional.ofNullable(secrets[name]) } as SecretsProvider
    }

    def "builds the client when the token secret resolves"() {
        given:
        def factory = new GithubCheckClientFactory(providing([GNOMISH_GITHUB_ACTIONS_TOKEN: 'tok']))

        expect:
        factory.create('https://api.github.com', 'acme/widgets') != null
    }

    // FR26: the owner/name split is exact — the built client polls the configured repository,
    // not an off-by-one slice of the coordinate. Asserted via Groovy's direct field access,
    // since the client exposes the coordinates only through the URLs it polls.
    def "the repo coordinate splits into owner and name exactly at the slash"() {
        given:
        def factory = new GithubCheckClientFactory(providing([GNOMISH_GITHUB_ACTIONS_TOKEN: 'tok']))

        when:
        def client = factory.create('https://api.github.com', 'acme/widgets')

        then:
        client.@owner == 'acme'
        client.@repo == 'widgets'
    }

    def "a missing token fails closed naming the secret"() {
        given:
        def factory = new GithubCheckClientFactory(providing([:]))

        when:
        factory.create('https://api.github.com', 'acme/widgets')

        then:
        def e = thrown(GithubCheckTokenException)
        e.message.contains('GNOMISH_GITHUB_ACTIONS_TOKEN')
    }

    def "the pin contribution is exactly the checkId workflow file"() {
        given:
        def factory = new GithubCheckClientFactory(providing([GNOMISH_GITHUB_ACTIONS_TOKEN: 'tok']))
        def check = new VerifyCheck.External(
                '.github/workflows/ci.yml', Duration.ofSeconds(1), Duration.ofSeconds(5),
                VerifyCheck.TimeoutClass.QUALITY)

        expect:
        factory.pinContributor().pinPaths(check) == ['.github/workflows/ci.yml'] as Set
    }

    // FR26: every malformed coordinate shape is rejected, including the exact edges — a leading
    // slash (empty owner) and a trailing slash (empty name) are configuration mistakes, never a
    // client with a blank coordinate half.
    def "an invalid repo coordinate is rejected"() {
        given:
        def factory = new GithubCheckClientFactory(providing([GNOMISH_GITHUB_ACTIONS_TOKEN: 'tok']))

        when:
        factory.create('https://api.github.com', repo)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(repo)

        where:
        repo << [
            'not-a-repo-ref',
            '/widgets',
            'acme/',
            'acme/widgets/extra'
        ]
    }
}
