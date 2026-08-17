package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.app.CheckClientFactory
import spock.lang.Specification

/**
 * FR9, FR2 of add-plugin-architecture: the built-in http provider is an ordinary SPI factory — a
 * public no-arg constructor, a {@code provider()} discriminator, collaborators as method arguments.
 *
 * FR15: it contributes no pin paths, since an arbitrary REST endpoint has no repo-side definition
 * file to pin; only a check's law-declared {@code pinPaths} pin it.
 *
 * FR11, FR17, D11: its credentials are named per check in the manifest, so it declares them through
 * the per-check half of the SPI's credential declaration rather than from a connection subsection.
 */
class HttpCheckClientFactorySpec extends Specification implements HttpCheckFixture {

    def "is a no-arg SPI factory declaring the http discriminator"() {
        when:
        def factory = new HttpCheckClientFactory()

        then:
        factory instanceof CheckClientFactory
        factory.provider() == 'http'
    }

    def "builds a client over the production exchange from an empty subsection"() {
        expect:
        new HttpCheckClientFactory().create(providing([:]), [:]) instanceof HttpExternalCheckClient
    }

    def "exposes its own params and subsection validators"() {
        given:
        def factory = new HttpCheckClientFactory()

        expect:
        factory.paramsValidator().get() instanceof HttpCheckParamsValidator
        factory.subsectionValidator().get() instanceof HttpCheckSubsectionValidator
    }

    // FR15: the provider contributes nothing, so a check declaring no pinPaths passes the pin guard
    //     vacuously — the empty-union rule of verification-hardening.
    def "contributes no pin paths, whatever the check declares"() {
        given:
        def factory = new HttpCheckClientFactory()

        expect:
        factory.pinContributor().pinPaths(check([url: URL])).isEmpty()
        factory.pinContributor().pinPaths(check([url: URL], 'another-check')).isEmpty()
    }

    // FR11, FR17: the manifest-named credential is declared back to the composition root, which is
    //     what puts it in the run's scrub / never-allowlist set.
    def "declares the credential its check names, and nothing when a check names none"() {
        given:
        def factory = new HttpCheckClientFactory()

        expect:
        factory.checkCredentialEnvVars([url: URL, auth: [credential: 'GNOMISH_SONAR_TOKEN']]) == [
            'GNOMISH_SONAR_TOKEN'
        ]
        factory.checkCredentialEnvVars([url: URL]) == []
    }

    // FR17: the connection-side declaration stays empty — this provider has no connection at all.
    def "declares no connection-side credential"() {
        expect:
        new HttpCheckClientFactory().credentialEnvVars([:]) == []
    }

    // NFR-S2, D5: what the operator's subsection configures is the egress allowlist, and the client
    //     the factory builds is guarded by it — a target on no entry never reaches a socket.
    def "the client it builds is guarded by the operator's egress allowlist"() {
        given:
        def client = new HttpCheckClientFactory().create(providing([:]), [allowlist: ['sonar.example.com']])

        when:
        def status = client.poll(check([url: 'https://evil.example.net/exfil']), null)

        then:
        status instanceof com.github.oinsio.gnomish.domain.engine.PollStatus.CannotVerify
        status.reason().contains('missing allowlist entry')
    }

    // NFR-S2: no allowlist means no reachable target — enabling the provider and saying where it may
    //     call are separate acts.
    def "a subsection with no allowlist permits nothing"() {
        given:
        def client = new HttpCheckClientFactory().create(providing([:]), [:])

        expect:
        client.poll(check([url: 'https://sonar.example.com/api']), null).reason().contains('missing allowlist entry')
    }

    // NFR-S2: without a run context nothing is interpolatable, so an interpolating check fails closed.
    def "the run-aware form supplies the run's variables to the client it builds"() {
        given:
        def runContext = { name ->
            Optional.of('gnomish/PROJ-42')
        } as com.github.oinsio.gnomish.app.CheckRunContext
        def client = new HttpCheckClientFactory()
                .create(providing([:]), [allowlist: ['sonar.example.com']], runContext)

        expect:
        client.runContext().value('task.branch').get() == 'gnomish/PROJ-42'
    }
}
