package com.github.oinsio.gnomish

import java.time.Duration
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.PlaceholdersResolver
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.convert.ApplicationConversionService
import spock.lang.Specification

/**
 * The binding half of {@link FactoryProperties}: the duration properties bind through the
 * {@code @ConstructorBinding} target with the same conversion service a running application binds
 * through, pinned with the binder alone, no context. FR5, UX1, design D8 of
 * bound-subprocess-commands; FR19, design D17 (as amended 2026-10-17) of
 * fix-terminal-receipt-convergence for the retired tail-drain key.
 */
class FactoryPropertiesBindingSpec extends Specification {

    // FR19, D17 as amended 2026-10-17 of fix-terminal-receipt-convergence: the retired
    //     factory.agent-cli-tail-drain-grace is an unknown key — an installation that still sets it
    //     keeps starting (Spring's ignoreUnknownFields default), and the value acts on nothing
    def "the retired tail-drain key still binds and changes no component"() {
        when:
        def properties = bindOrCreate(['factory.agent-cli-tail-drain-grace': '30s'])

        then:
        properties == bindOrCreate([:])
    }

    // FR5/D8 of bound-subprocess-commands: the three deadlines bind under their kebab-case names
    //     through the same annotated constructor — the property an operator writes reaches the record
    def "#property binds to the record"() {
        when:
        def properties = bind([(property): '90s'])

        then:
        accessor(properties) == Duration.ofSeconds(90)

        where:
        property | accessor
        'factory.git-network-timeout' | { it.gitNetworkTimeout() }
        'factory.docker-command-timeout' | { it.dockerCommandTimeout() }
        'factory.check-command-timeout' | { it.checkCommandTimeout() }
    }

    // the constructor-bound record is created even when no known key is set, as
    //     ConfigurationPropertiesBinder does for a value object; no NoUnboundElementsBindHandler, the
    //     ignoreUnknownFields default
    private static FactoryProperties bindOrCreate(Map<String, String> properties) {
        binder(properties).bindOrCreate('factory', Bindable.of(FactoryProperties))
    }

    private static FactoryProperties bind(Map<String, String> properties) {
        binder(properties).bind('factory', Bindable.of(FactoryProperties)).get()
    }

    private static Binder binder(Map<String, String> properties) {
        new Binder(
                [
                    new MapConfigurationPropertySource(properties)
                ] as List<ConfigurationPropertySource>,
                null as PlaceholdersResolver,
                ApplicationConversionService.getSharedInstance())
    }
}
