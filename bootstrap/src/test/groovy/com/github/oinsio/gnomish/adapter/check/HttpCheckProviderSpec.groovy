package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.adapter.check.http.HttpCheckClientFactory
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * FR9, D4 of add-plugin-architecture: the built-in http provider resolves from the same registry as
 * any other provider — the {@code ServiceLoader} pass finds it through an ordinary {@code
 * META-INF/services} entry, the operator selects it with an (empty) {@code factory.check.http}
 * subsection, and the dispatching composite routes a {@code provider: http} check to it. "Built-in"
 * is packaging, not privilege.
 *
 * <p>FR11, FR17, D11: because this provider's credentials are named per check rather than per
 * connection, the composition root asks the loaded pipeline too, so a manifest-named credential
 * joins the run's scrub / never-allowlist set like a connection-declared one.
 */
class HttpCheckProviderSpec extends Specification {

    private static final SecretsProvider NO_SECRETS = { name ->
        Optional.empty()
    } as SecretsProvider

    // FR9: discovered through the same pass as github, with no core source naming the class.
    def "the http provider is discovered through the ordinary ServiceLoader pass"() {
        when:
        def registry = CheckClientDiscovery.discover()

        then:
        registry[HttpCheckClientFactory.PROVIDER] instanceof HttpCheckClientFactory
        registry.keySet().containsAll(['github', 'http'])
    }

    // FR9, D4: selection is the same declaration as any provider's — an empty subsection, since the
    //     provider has no connection to configure.
    def "an empty factory.check.http subsection selects it and passes the startup gate"() {
        when:
        def registry = CheckClientDiscovery.discover()
        CheckClientConfiguration.requireValidSubsections([http: [:]], registry, ConnectionProfiles.none())

        then:
        noExceptionThrown()
    }

    // FR9, D10: the engine port is unchanged — the composite resolves the client by the check's own
    //     provider field, the same way it resolves github's.
    def "the dispatching composite routes a provider: http check to the http client"() {
        given:
        def registry = CheckClientDiscovery.discover()
        def composite = new ProviderDispatchingExternalCheckClient(registry, [http: [:]], NO_SECRETS)

        when: 'a check whose auth names a credential no secrets adapter resolves'
        def status = composite.poll(httpCheck([url: 'https://ci.example.invalid/status',
            auth: [credential: 'MISSING_TOKEN']]), null)

        then: 'the http client answered — fail-closed, naming its own secret'
        status instanceof PollStatus.CannotVerify
        status.reason().contains('MISSING_TOKEN')
    }

    // FR15: only law-declared pinPaths pin an http check; the provider contributes none, so a check
    //     declaring none passes the pin guard vacuously.
    def "the composite's pin contribution for an http check is empty"() {
        given:
        def composite = new ProviderDispatchingExternalCheckClient(
                CheckClientDiscovery.discover(), [http: [:]], NO_SECRETS)

        expect:
        composite.pinContributor().pinPaths(httpCheck([url: 'https://ci.example.invalid/status'])).isEmpty()
    }

    // FR11, FR17, D11: the manifest half of the credential declaration — asked of the provider, over
    //     params core never interprets.
    def "a manifest-named http credential joins the declared credential set"() {
        given:
        def registry = CheckClientDiscovery.discover()

        when:
        def names = CheckProviderSeam.checkCredentialEnvVars(pipelineWith(
                        httpCheck([url: 'https://ci.example.invalid/a', auth: [credential: 'GNOMISH_SONAR_TOKEN']]),
                        httpCheck([url: 'https://ci.example.invalid/b'])), registry)

        then:
        names == ['GNOMISH_SONAR_TOKEN']
    }

    // FR17: a provider drawing its credential from a connection subsection declares nothing per
    //     check, and a check naming an undiscovered provider contributes nothing — the load seam has
    //     already reported that one.
    def "checks of other providers contribute no per-check credential"() {
        given:
        def registry = CheckClientDiscovery.discover()
        def github = new VerifyCheck.External('ci.yml', 'github', Duration.ofSeconds(1),
                Duration.ofSeconds(5), VerifyCheck.TimeoutClass.QUALITY)
        def unknown = new VerifyCheck.External('x', 'nobody-serves-this', Duration.ofSeconds(1),
                Duration.ofSeconds(5), VerifyCheck.TimeoutClass.QUALITY)

        expect:
        CheckProviderSeam.checkCredentialEnvVars(pipelineWith(github, unknown), registry).isEmpty()
    }

    private static VerifyCheck.External httpCheck(Map<String, Object> params) {
        new VerifyCheck.External('quality-gate', HttpCheckClientFactory.PROVIDER, params,
                Duration.ofSeconds(1), Duration.ofSeconds(30), VerifyCheck.TimeoutClass.QUALITY, [])
    }

    private static PipelineDefinition pipelineWith(VerifyCheck... checks) {
        def limits = new AutonomyLimits(1)
        new PipelineDefinition('1', limits, [
            new StageDefinition('verify', 'purpose', [], [], null, 'instructions.md',
            checks as List, limits, null)
        ])
    }
}
