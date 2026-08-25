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
 * FR7 of fix-round-stdout-drain, the binding half: the record's own compact
 * constructor rejects a non-positive grace ({@code FactoryPropertiesSpec}), but
 * a *malformed* one — a string that is no duration at all — never reaches the
 * constructor. It is refused one layer up, by Spring's constructor binding, and
 * FR7 requires that to be a startup error rather than a silent fall back to the
 * default. This spec pins that layer with the binder alone, no context: the same
 * {@code @ConstructorBinding} target and the same conversion service a running
 * application binds through.
 */
class FactoryPropertiesBindingSpec extends Specification {

    // FR7: a well-formed duration binds through the annotated canonical constructor
    def "a well-formed grace binds to the record"() {
        when:
        def properties = bind(['factory.agent-cli-tail-drain-grace': '30s'])

        then:
        properties.agentCliTailDrainGrace() == Duration.ofSeconds(30)
    }

    // FR7/D2: a malformed grace is a startup error, not a silent default
    def "a malformed grace of #description fails binding"() {
        when:
        bind(['factory.agent-cli-tail-drain-grace': malformed])

        then:
        def failure = thrown(BindException)
        failure.message.contains('factory.agent-cli-tail-drain-grace')

        where:
        malformed | description
        'banana' | 'a word'
        '5 seconds' | 'a spelled-out unit'
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

    private static FactoryProperties bind(Map<String, String> properties) {
        new Binder(
                [
                    new MapConfigurationPropertySource(properties)
                ] as List<ConfigurationPropertySource>,
                null as PlaceholdersResolver,
                ApplicationConversionService.getSharedInstance())
                .bind('factory', Bindable.of(FactoryProperties))
                .get()
    }
}
