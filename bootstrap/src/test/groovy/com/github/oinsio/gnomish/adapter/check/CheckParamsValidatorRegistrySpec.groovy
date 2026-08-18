package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.CheckClientFactory
import com.github.oinsio.gnomish.app.CheckParamsValidator
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import spock.lang.Specification

/**
 * {@code CheckClientConfiguration#checkParamsValidatorRegistry}: the registry the pipeline loader
 * grades each {@code external} check against (FR6, FR13, design D1/D3 of add-plugin-architecture).
 *
 * <p>Two claims, both load-time: the registry is derived from the discovered factory registry, so
 * it cannot drift from it; and it keys <em>every</em> discovered provider, including one that
 * grades no params — the loader reads its key set as the discovered provider set, so a missing key
 * has to mean "no jar serves this provider" and nothing else.
 *
 * <p>Implements FR6, FR13 of add-plugin-architecture.
 */
class CheckParamsValidatorRegistrySpec extends Specification {

    private final configuration = new CheckClientConfiguration()

    // FR6: a provider's own validator is the one the loader will call for its params.
    def "each discovered provider contributes its own params validator"() {
        given: 'a provider exposing a params validator that rejects everything'
        def factory = new PluginStandInCheckClientFactory(
                params: { String file, String where, Map params ->
                    [
                        new ConfigError(file, where, 'stand-in rejects')
                    ]
                } as CheckParamsValidator)

        when:
        def registry = configuration.checkParamsValidatorRegistry(
                [(PluginStandInCheckClientFactory.PROVIDER): factory] as Map<String, CheckClientFactory>)

        then:
        registry[PluginStandInCheckClientFactory.PROVIDER].validate('m', 'verify[0].params', [:]) ==
        [
            new ConfigError('m', 'verify[0].params', 'stand-in rejects')
        ]
    }

    // FR6/UX1: a provider grading no params still appears — otherwise the loader would read its
    //     absence as "undiscovered" and report a served provider as unknown.
    def "a provider that grades no params is still keyed, with an accept-everything validator"() {
        when:
        def registry = configuration.checkParamsValidatorRegistry(
                [(PluginStandInCheckClientFactory.PROVIDER): new PluginStandInCheckClientFactory(params: null)]
                as Map<String, CheckClientFactory>)

        then:
        registry.keySet() == [
            PluginStandInCheckClientFactory.PROVIDER
        ] as Set
        registry[PluginStandInCheckClientFactory.PROVIDER].validate('m', 'verify[0].params', [any: 'thing']).isEmpty()
    }

    // D1/D3: derived from the factory registry, so the two are keyed identically by construction.
    def "the registry is keyed exactly like the factory registry it is derived from"() {
        when:
        def registry = configuration.checkParamsValidatorRegistry(CheckClientDiscovery.discover())

        then:
        registry.keySet() == CheckClientDiscovery.discover().keySet()
        registry.keySet().contains('github')
    }
}
