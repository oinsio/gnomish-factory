package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.app.ConnectionProfiles
import spock.lang.Specification

/**
 * {@link CheckProviderSeam} and the startup gate over it: the operator-side
 * {@code factory.check.<provider>} subsections are graded by the providers themselves, and the
 * run's check-credential set is the union of what those providers declare (FR4, FR5, FR17, design
 * D11/D12 of add-plugin-architecture).
 *
 * <p>Core owns exactly one judgement here — a subsection naming a provider nobody discovered — and
 * delegates every content question. That is what lets a plugin state its own connection rule and a
 * plugin credential be scrubbed with no core constant naming either.
 */
class CheckProviderSeamSpec extends Specification {

    private static final String FILE = 'application.yaml'

    private static final Map REGISTRY = [
        (GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory(),
        (PluginStandInCheckClientFactory.PROVIDER): new PluginStandInCheckClientFactory()
    ]

    private static final Map GITHUB_SUBSECTION = [('api-url'): 'https://api.github.com', repo: 'acme/widgets']

    // FR4: nothing configured is nothing to complain about — the seam is silent, not defaulting.
    def "no configured provider yields no errors and no credentials"() {
        expect:
        CheckProviderSeam.validate(FILE, [:], REGISTRY).isEmpty()
        CheckProviderSeam.credentialEnvVars([:], REGISTRY).isEmpty()
    }

    // FR5, task 2.7: a valid subsection is passed to the provider's own validator and comes back
    //     clean; core interprets none of its keys.
    def "a valid subsection is delegated and reports nothing"() {
        expect:
        CheckProviderSeam.validate(FILE, [github: GITHUB_SUBSECTION], REGISTRY).isEmpty()
    }

    // FR5, task 2.7: a malformed subsection is a LOCATED ConfigError produced by the provider's
    //     own subsectionValidator(), reported under factory.check.<provider>.
    def "a malformed subsection is a located error from the provider's own validator"() {
        when:
        def errors = CheckProviderSeam.validate(FILE, [github: [('api-url'): 'https://api.github.com']], REGISTRY)

        then:
        errors.size() == 1
        errors[0].file() == FILE
        errors[0].where() == 'factory.check.github.repo'
    }

    // FR4: a subsection naming a provider no jar serves is core's own judgement — the one thing the
    //     seam decides itself — and it names the discovered set so the operator sees the choices.
    def "a subsection naming an undiscovered provider is a located error naming the discovered set"() {
        when:
        def errors = CheckProviderSeam.validate(FILE, [sonar: [('api-url'): 'https://sonar.example']], REGISTRY)

        then:
        errors.size() == 1
        errors[0].where() == 'factory.check.sonar'
        errors[0].message().contains("unknown check provider 'sonar'")
        errors[0].message().contains('github')
    }

    // NFR-R1, task 2.7: every provider's complaints aggregate into one report, in provider-name
    //     order — an operator fixes them all in one pass rather than one restart at a time.
    def "problems from several providers aggregate in provider-name order"() {
        when:
        def errors = CheckProviderSeam.validate(
                FILE,
                [github: [:], (PluginStandInCheckClientFactory.PROVIDER): [:]],
                REGISTRY)

        then:
        errors*.where() == [
            'factory.check.github.api-url',
            'factory.check.github.repo',
            'factory.check.plugin-stand-in.endpoint'
        ]
    }

    // FR17, D11: the run's check-credential set is the union of the SELECTED providers' own
    //     declarations — no core source names a vendor constant to build it.
    def "credential names are the union of the configured providers' own declarations"() {
        when:
        def names = CheckProviderSeam.credentialEnvVars(
                [github: GITHUB_SUBSECTION,
                    (PluginStandInCheckClientFactory.PROVIDER): [endpoint: 'https://plugin.example', credential: 'PLUGIN_TOKEN']],
                REGISTRY)

        then:
        names as Set == [
            GithubCheckClientFactory.TOKEN_ENV_VAR,
            'PLUGIN_TOKEN'
        ] as Set
    }

    // FR17, D11: a credential name supplied as configuration data — not a compile-time constant —
    //     reaches the scrub set all the same, which is exactly what a no-arg declaration could not do.
    def "a credential named by configuration data is declared like any other"() {
        expect:
        CheckProviderSeam.credentialEnvVars(
                [(PluginStandInCheckClientFactory.PROVIDER): [endpoint: 'e', credential: 'RENAMED_TOKEN']],
                REGISTRY) == ['RENAMED_TOKEN']
    }

    // FR17: a provider that is configured but declares no credential contributes nothing, and a
    //     subsection for an undiscovered provider contributes nothing either — validate() has
    //     already turned the latter into a startup error, so this can never under-scrub in use.
    def "a provider with no declared credential contributes nothing"() {
        expect:
        CheckProviderSeam.credentialEnvVars(
                [(PluginStandInCheckClientFactory.PROVIDER): [endpoint: 'e'], sonar: [:]],
                REGISTRY).isEmpty()
    }

    // FR4, NFR-R1: the startup gate turns the aggregated report into one failure listing every
    //     problem, rather than a first-problem abort or a mid-take surprise.
    def "the startup gate fails naming every located problem"() {
        when:
        CheckClientConfiguration.requireValidSubsections([github: [:], sonar: [:]], REGISTRY, ConnectionProfiles.none())

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('factory.check.github.api-url')
        e.message.contains('factory.check.github.repo')
        e.message.contains("unknown check provider 'sonar'")
    }

    // FR4: valid configuration passes the gate and yields the registry the composition root uses.
    def "the startup gate passes valid configuration"() {
        given:
        def properties = new FactoryProperties(null, null, null, null, [github: GITHUB_SUBSECTION])

        when:
        def registry = new CheckClientConfiguration().checkClientRegistry(properties)

        then:
        registry[GithubCheckClientFactory.PROVIDER] instanceof GithubCheckClientFactory
    }
}
