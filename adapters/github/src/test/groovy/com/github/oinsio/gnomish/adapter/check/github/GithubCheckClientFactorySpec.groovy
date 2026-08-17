package com.github.oinsio.gnomish.adapter.check.github

import com.github.oinsio.gnomish.app.CheckClientFactory
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * FR26 of add-sandbox-core; FR8, NFR-S1 of add-external-check-github-actions: the factory builds
 * the GitHub Actions external-check client from its operator subsection, resolving the token by
 * name through the {@code SecretsProvider} — a missing or blank secret fails closed naming the
 * secret; the pin contribution is exactly the {@code checkId} workflow file.
 *
 * FR2, FR5, FR17 of add-plugin-architecture: the same class is now one {@code CheckClientFactory}
 * among the discovered registry — a public no-arg constructor, a {@code provider()} discriminator,
 * and its credential declared through the SPI rather than named by core.
 */
class GithubCheckClientFactorySpec extends Specification {

    private static final Map<String, Object> SUBSECTION =
    [('api-url'): 'https://api.github.com', repo: 'acme/widgets']

    private static SecretsProvider providing(Map<String, String> secrets) {
        { name -> Optional.ofNullable(secrets[name]) } as SecretsProvider
    }

    // FR2, FR5: ServiceLoader can only instantiate a public no-arg constructor, and the registry is
    //     keyed by the discriminator this factory declares for itself.
    def "is a no-arg SPI factory declaring the github discriminator"() {
        when:
        def factory = new GithubCheckClientFactory()

        then:
        factory instanceof CheckClientFactory
        factory.provider() == 'github'
    }

    def "builds the client when the token secret resolves"() {
        given:
        def factory = new GithubCheckClientFactory()

        expect:
        factory.create(providing([GNOMISH_GITHUB_ACTIONS_TOKEN: 'tok']), SUBSECTION) != null
    }

    // FR26: the owner/name split is exact — the built client polls the configured repository,
    // not an off-by-one slice of the coordinate. Asserted through the client record's own
    // component accessors, the coordinates it later polls with.
    def "the repo coordinate splits into owner and name exactly at the slash"() {
        given:
        def factory = new GithubCheckClientFactory()

        when:
        def client = factory.create(providing([GNOMISH_GITHUB_ACTIONS_TOKEN: 'tok']), SUBSECTION)

        then:
        client.owner() == 'acme'
        client.repo() == 'widgets'
    }

    def "a missing token fails closed naming the secret"() {
        given:
        def factory = new GithubCheckClientFactory()

        when:
        factory.create(providing([:]), SUBSECTION)

        then:
        def e = thrown(GithubCheckTokenException)
        e.message.contains('GNOMISH_GITHUB_ACTIONS_TOKEN')
    }

    // FR17, D11 of add-plugin-architecture: the credential name travels through the SPI, so the
    //     composition root can scrub it without any core source naming the constant.
    def "declares its credential env var through the SPI"() {
        expect:
        new GithubCheckClientFactory().credentialEnvVars(SUBSECTION) == [
            'GNOMISH_GITHUB_ACTIONS_TOKEN'
        ]
    }

    // FR4, FR5, FR6: both validators are exposed through the factory rather than discovered as
    //     separate SPIs, so neither registry can drift from the provider registry.
    def "exposes its own subsection and params validators"() {
        given:
        def factory = new GithubCheckClientFactory()

        expect:
        factory.subsectionValidator().get() instanceof GithubCheckSubsectionValidator
        factory.paramsValidator().get() instanceof GithubCheckParamsValidator
    }

    def "the pin contribution is exactly the checkId workflow file"() {
        given:
        def factory = new GithubCheckClientFactory()
        def check = new VerifyCheck.External(
                '.github/workflows/ci.yml', 'github', Duration.ofSeconds(1), Duration.ofSeconds(5),
                VerifyCheck.TimeoutClass.QUALITY)

        expect:
        factory.pinContributor().pinPaths(check) == ['.github/workflows/ci.yml'] as Set
    }

    // FR26: every malformed coordinate shape is rejected, including the exact edges — a leading
    // slash (empty owner) and a trailing slash (empty name) are configuration mistakes, never a
    // client with a blank coordinate half.
    def "an invalid repo coordinate is rejected"() {
        given:
        def factory = new GithubCheckClientFactory()

        when:
        factory.create(providing([GNOMISH_GITHUB_ACTIONS_TOKEN: 'tok']), [('api-url'): 'https://api.github.com', repo: repo])

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

    // FR5: a subsection missing a key the validator requires can only be reached by bypassing the
    //     load seam; the factory still refuses rather than building a client with a null coordinate.
    def "a subsection missing a required key is refused"() {
        given:
        def factory = new GithubCheckClientFactory()

        when:
        factory.create(providing([GNOMISH_GITHUB_ACTIONS_TOKEN: 'tok']), [('api-url'): 'https://api.github.com'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('factory.check.github.repo')
    }
}
