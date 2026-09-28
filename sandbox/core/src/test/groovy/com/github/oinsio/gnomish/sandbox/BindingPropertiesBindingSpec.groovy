package com.github.oinsio.gnomish.sandbox

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import spock.lang.Specification

/**
 * FR6, M4 of fix-operator-blockers: the documented {@code factory.bindings.default} key binds
 * the default stage binding. Bound through a real Spring {@link Binder}, the way the factory
 * reads its configuration — {@link BindingPropertiesSpec} constructs the record directly, which
 * is why a key that never bound stayed green.
 */
class BindingPropertiesBindingSpec extends Specification {

    private static BindingProperties bind(Map<String, String> properties) {
        // A list, not the source itself: the source is an Iterable of names, and Groovy would pick
        // Binder(Iterable<ConfigurationPropertySource>) and treat each name as a source.
        new Binder([
            new MapConfigurationPropertySource(properties)
        ])
        .bindOrCreate('factory.bindings', BindingProperties)
    }

    // FR6, M4: the key the guides and the error message name takes effect
    def "FR6: factory.bindings.default=host binds the default binding"() {
        expect:
        bind(['factory.bindings.default': 'host']).defaultBinding() == 'host'
    }

    // FR6, design D4 migration note: the undocumented spelling that bound by accident binds nothing
    def "FR6: factory.bindings.default-binding no longer binds the default binding"() {
        expect:
        bind(['factory.bindings.default-binding': 'host']).defaultBinding() == null
    }

    // FR6: the per-stage overrides still bind beside the default
    def "FR6: per-stage overrides bind beside the documented default"() {
        when:
        def properties = bind(['factory.bindings.default': 'container', 'factory.bindings.stages.build': 'host'])

        then:
        properties.defaultBinding() == 'container'
        properties.stages() == [build: 'host']
    }

    // FR6: an empty configuration leaves the default unset and the overrides empty
    def "FR6: with no bindings configured the default stays unset and the overrides empty"() {
        when:
        def properties = bind([:])

        then:
        properties.defaultBinding() == null
        properties.stages() == [:]
    }
}
