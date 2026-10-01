package com.github.oinsio.gnomish.config

import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification

/**
 * {@link OperatorSources#add}: the project block above the host block, both just below the
 * command line and the JVM system properties, whichever of those the environment carries (FR5).
 *
 * <p>Implements FR5 of add-project-registry.
 */
class OperatorSourcesSpec extends Specification {

    def project = new MapPropertySource('project', ['factory.serve.slots': '2'])
    def host = new MapPropertySource('host', ['factory.serve.slots': '1'])

    // FR5: below the system properties, the project above the host
    def "FR5: the blocks go just below the system properties"() {
        given:
        def environment = new StandardEnvironment()
        environment.propertySources.addFirst(new SimpleCommandLinePropertySource(['--x=1'] as String[]))

        when:
        OperatorSources.add(environment, project, host)

        then:
        environment.propertySources*.name.take(4) == [
            'commandLineArgs',
            'systemProperties',
            'project',
            'host'
        ]
    }

    // FR5: a project-less command places the host block where the project block would have gone
    def "FR5: with no project block the host block takes its place"() {
        given:
        def environment = new StandardEnvironment()

        when:
        OperatorSources.add(environment, null, host)

        then:
        environment.propertySources*.name.take(2) == ['systemProperties', 'host']
    }

    // FR5: with neither anchor present the blocks lead, still in their order
    def "FR5: with no command line and no system properties the blocks come first"() {
        given:
        def environment = new StandardEnvironment()
        environment.propertySources.remove('systemProperties')

        when:
        OperatorSources.add(environment, project, host)

        then:
        environment.propertySources*.name.take(2) == ['project', 'host']
    }
}
