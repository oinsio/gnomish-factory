package com.github.oinsio.gnomish

import spock.lang.Specification

/**
 * BindingProperties: immutable typed configuration record for the per-stage
 * adapter bindings (design D8, D13; FR14 of add-sandbox-core). Binding names are
 * carried as raw strings — the container-default rule and the string→binding
 * validation live in BindingResolver, not here — so this record only defaults
 * the collections and stays decoupled from the adapter layer.
 *
 * FR14: the operator-owned bindings are carried, typed.
 */
class BindingPropertiesSpec extends Specification {

    // D13: defaultBinding stays null when unset (BindingResolver owns the container default)
    def "defaultBinding is null when unset and exposed unchanged when set"() {
        expect: 'unset default stays null so the resolver applies the container default'
        new BindingProperties(null, null).defaultBinding() == null

        and: 'a configured default is exposed unchanged'
        new BindingProperties('host', null).defaultBinding() == 'host'
    }

    // FR14: the per-stage overrides default to an empty map when unset
    def "stages default to an empty map when unset"() {
        expect: 'the unset stage map is empty'
        new BindingProperties(null, null).stages() == [:]
    }

    // FR14: per-stage overrides are exposed unchanged
    def "per-stage overrides are exposed unchanged"() {
        expect: 'the accessor returns exactly the configured overrides'
        new BindingProperties(null, [build: 'host', test: 'container']).stages() == [build: 'host', test: 'container']
    }

    // FR14: the stage map is defensively copied and exposed immutable
    def "the stage map is defensively copied and exposed immutable"() {
        given: 'a mutable source map'
        def source = [build: 'host']

        when: 'the properties are built and the source grows afterwards'
        def properties = new BindingProperties(null, source)
        source.put('test', 'container')

        then: 'the exposed map holds only the original entry'
        properties.stages() == [build: 'host']

        when: 'a caller tries to mutate the exposed map'
        properties.stages().put('intruder', 'host')

        then: 'the map rejects the mutation'
        thrown(UnsupportedOperationException)
    }

    // FR14: the properties type is an immutable record without setters
    def "the properties type is an immutable record without setter methods"() {
        expect: 'it is a Java record'
        BindingProperties.isRecord()

        and: 'no public method follows the mutable setter convention'
        BindingProperties.methods.every { !(it.name.startsWith('set') && it.parameterCount > 0) }
    }
}
