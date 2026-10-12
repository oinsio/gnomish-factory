package com.github.oinsio.gnomish

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.PlaceholdersResolver
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.convert.ApplicationConversionService
import spock.lang.Specification

/**
 * A list inside a raw {@code factory.check.<provider>} or {@code factory.connections.<name>}
 * subsection reaches the provider as a list, although Spring's Binder hands it over as a map keyed
 * by index (finding F130 of tighten-spec-contract).
 *
 * <p>Implements FR5 of add-project-registry; FR4 of add-plugin-architecture.
 */
class IndexedListsSpec extends Specification {

    // FR4: the Binder's index-keyed map for an operator's list binds back to the list, in index order
    def "a list bound by the Binder into a raw subsection is a list in index order"() {
        when:
        def properties = bind([
            'factory.check.http.allowlist[1]': 'ci.example.com',
            'factory.check.http.allowlist[0]': 'sonar.example.com',
            'factory.connections.acme.scopes[0]': 'repo'])

        then:
        properties.check().http.allowlist == [
            'sonar.example.com',
            'ci.example.com'
        ]
        properties.connections().acme.scopes == ['repo']
    }

    // FR5: a list of maps and a list of lists come back whole, at every depth
    def "the restoration recurses into list elements and map values"() {
        when:
        def restored = IndexedLists.inSubsection([
            rules: ['0': [host: 'a', ports: ['0': '443', '1': '8443']], '1': [host: 'b']],
            grid: ['0': ['0': 'x'], '1': ['0': 'y', '1': 'z']],
            nested: [inner: ['0': 'only']]])

        then:
        restored.rules == [
            [host: 'a', ports: ['443', '8443']],
            [host: 'b']
        ]
        restored.grid == [['x'], ['y', 'z']]
        restored.nested == [inner: ['only']]
    }

    // FR5: only keys exactly "0".."n-1" make a list; every other map stays a map, scalars pass through
    def "a map that is not exactly index-keyed stays a map: #description"() {
        when:
        def restored = IndexedLists.inSubsection([value: value])

        then:
        restored.value == value
        restored.value instanceof Map

        where:
        description | value
        'empty' | [:]
        'a gap in the indices' | ['0': 'a', '2': 'c']
        'indices from one' | ['1': 'a', '2': 'b']
        'a named key besides' | ['0': 'a', name: 'b']
        'padded index' | ['00': 'a']
        'integer keys' | [(0): 'a']
    }

    // FR5: the subsection itself stays a map even when its own keys are indices
    def "the subsection's own keys are never read as a list"() {
        expect:
        IndexedLists.inSubsection(['0': 'a']) == ['0': 'a']
        IndexedLists.inSubsection([url: 'https://x']).url == 'https://x'
    }

    // FR5: the restored content is unmodifiable at every depth
    def "a restored #shape is unmodifiable"() {
        given:
        def restored = IndexedLists.inSubsection([list: ['0': 'a'], map: [k: 'v']])

        when:
        mutate(restored)

        then:
        thrown(UnsupportedOperationException)

        where:
        shape | mutate
        'list' | { it.list.add('b') }
        'map' | { it.map.put('k2', 'v2') }
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
