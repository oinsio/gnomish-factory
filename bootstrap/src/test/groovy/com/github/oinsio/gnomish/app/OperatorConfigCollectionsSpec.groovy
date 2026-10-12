package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import java.nio.file.Path
import org.springframework.boot.context.properties.bind.Binder
import spock.lang.Specification
import spock.lang.TempDir

/**
 * How a collection-valued {@code factory.*} key combines across the ordered sources (FR5): the
 * host file and the project file both feed {@code factory.connections}, a key every source may set,
 * and what the factory binds is read back through the production {@link FactoryProperties}.
 *
 * <p>Implements FR5 of add-project-registry.
 */
class OperatorConfigCollectionsSpec extends Specification {

    @TempDir
    Path tmp

    OperatorConfigLoaderHarness harness
    Path widgets

    def setup() {
        harness = new OperatorConfigLoaderHarness(tmp)
        widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
    }

    // Scenario: operator/operator-configuration / Map-valued keys merge by key across sources
    def "FR5: a map-valued key merges by key, the project file winning an entry both set"() {
        given: 'the host file sets two profiles, the project file one of them and a third'
        harness.hostConfig('''\
            factory:
              connections:
                acme:
                  endpoint: https://host.example
                corp:
                  endpoint: https://corp.example
            '''.stripIndent())
        harness.projectConfig('widgets', '''\
            factory:
              connections:
                acme:
                  endpoint: https://project.example
                lab:
                  endpoint: https://lab.example
            '''.stripIndent())

        when:
        def connections = bound().connections()

        then: 'every entry of either source is present, the higher source winning the shared one'
        connections.keySet() == ['acme', 'corp', 'lab'] as Set
        connections.acme.endpoint == 'https://project.example'
        connections.corp.endpoint == 'https://corp.example'
        connections.lab.endpoint == 'https://lab.example'
    }

    // Scenario: operator/operator-configuration / List-valued keys are replaced whole
    def "FR5: a list-valued key set by both files is the project file's list alone"() {
        given: 'the host file sets a longer list than the project file'
        harness.hostConfig('''\
            factory:
              connections:
                acme:
                  scopes: [host-a, host-b, host-c]
            '''.stripIndent())
        harness.projectConfig('widgets', '''\
            factory:
              connections:
                acme:
                  scopes: [project-a]
            '''.stripIndent())

        when:
        def scopes = bound().connections().acme.scopes

        then: 'no element of the host list survives, not even past the project list\'s end'
        scopes == ['project-a']
    }

    // FR5 (F106): the command line spells a list as one comma-separated value; it claims the list whole
    def "FR5: a comma-separated list on the command line replaces the files' list whole"() {
        given:
        harness.projectConfig('widgets', '''\
            factory:
              connections:
                acme:
                  scopes: [project-a, project-b]
            '''.stripIndent())

        when:
        def loaded = harness.load([
            'status',
            "--dir=$widgets".toString(),
            '--factory.connections.acme.scopes=cli-a,cli-b'
        ])
        def scopes = Binder.get(loaded.environment).bindOrCreate('factory', FactoryProperties).connections().acme.scopes

        then:
        scopes == 'cli-a,cli-b'
    }

    private FactoryProperties bound() {
        def loaded = harness.load([
            'status',
            "--dir=$widgets".toString()
        ])
        Binder.get(loaded.environment).bindOrCreate('factory', FactoryProperties)
    }
}
