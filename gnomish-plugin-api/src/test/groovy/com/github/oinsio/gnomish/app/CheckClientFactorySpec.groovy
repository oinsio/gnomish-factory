package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.lang.reflect.Modifier
import java.time.Duration
import spock.lang.Specification

/**
 * The {@link CheckClientFactory} SPI's own defaults (FR5, FR15, FR17, design D3 of
 * add-plugin-architecture): what a provider gets for free when it implements only the two
 * mandatory members. The defaults are the minimum-viable provider's contract, so each has to be a
 * real, usable value rather than a null the composition root would have to defend against — a
 * provider declaring no credential, grading no params or subsection, and contributing no pin path
 * must still be wired exactly like one that does all four.
 */
class CheckClientFactorySpec extends Specification {

    /** The minimum a provider must implement: a discriminator and a client. */
    private static class MinimalFactory implements CheckClientFactory {

        @Override
        String provider() {
            'minimal'
        }

        @Override
        ExternalCheckClient create(CheckClientContext context) {
            { check, workspace -> new PollStatus.Pass() } as ExternalCheckClient
        }
    }

    private final CheckClientFactory factory = new MinimalFactory()

    private static VerifyCheck.External check() {
        new VerifyCheck.External(
                'ci', 'sample', Duration.ofSeconds(1), Duration.ofSeconds(5), VerifyCheck.TimeoutClass.QUALITY)
    }

    // FR5: the two mandatory members are all a provider must supply to be discovered and usable.
    def "a provider implementing only the mandatory members is usable"() {
        expect:
        factory.provider() == 'minimal'
        factory.create(new FixedCheckClientContext({ _ ->
            Optional.empty()
        } as SecretsProvider, [:]))
        .poll(check(), Stub(Workspace)) instanceof PollStatus.Pass
    }

    // FR23 of supervise-daemon-loops-and-embed-dashboard: one abstract create, taking the context,
    //     and no default overload delegating to another — the run context and the time equipment
    //     ride the context rather than a second signature.
    def "FR23: the factory declares exactly one create, abstract, taking the context"() {
        when:
        def creates = CheckClientFactory.declaredMethods.findAll {
            it.name == 'create'
        }

        then:
        creates.size() == 1
        creates[0].parameterTypes as List == [CheckClientContext]
        Modifier.isAbstract(creates[0].modifiers)
        !creates[0].isDefault()
    }

    // FR6: a provider defining no per-check params grades none — the loader asks and is told so
    //     explicitly, rather than having to distinguish "no validator" from "null".
    def "params validation defaults to none"() {
        expect:
        factory.paramsValidator().isEmpty()
    }

    // FR4: a provider whose operator subsection is opaque grades no content.
    def "subsection validation defaults to none"() {
        expect:
        factory.subsectionValidator().isEmpty()
    }

    // FR17, D11: a provider reading no credential from the environment declares an empty list, so
    //     the composition root's union over every provider needs no null handling.
    def "the credential declaration defaults to empty rather than null"() {
        when:
        def declared = factory.credentialEnvVars([('api-url'): 'https://example.invalid'])

        then:
        declared != null
        declared.isEmpty()
    }

    // FR15: the default pin contribution is a real contributor contributing nothing — the pin-check
    //     guard unions it unconditionally, so a null here would break every pinless provider.
    def "the pin contribution defaults to a real, empty contributor"() {
        when:
        def contributor = factory.pinContributor()

        then:
        contributor != null
        contributor.pinPaths(check()) == [] as Set
    }
}
