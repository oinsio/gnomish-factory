package com.github.oinsio.gnomish.config

import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import java.nio.file.Path
import org.springframework.boot.origin.Origin
import org.springframework.boot.origin.OriginLookup
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.core.env.AbstractEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link OperatorSources#add}: the project block above the host block, both just below the
 * command line and the JVM system properties, whichever of those the environment carries (FR5);
 * and where {@link OperatorSources#check} says a refused key was found (UX1).
 *
 * <p>Implements FR5, UX1 of add-project-registry.
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

    // UX1: a text origin with a resource is named by the resource and its one-based line
    def "UX1: a key with a tracked resource is found at that resource's line"() {
        given:
        def file = new FileSystemResource(tmp.resolve('extra.yaml'))

        expect:
        foundIn(new TextOriginSource('extra', file)) == "found in ${file.description}:3"
    }

    // UX1: a text origin that names no resource falls back to the source's name
    def "UX1: a key whose text origin has no resource is found in the source's name"() {
        expect:
        foundIn(new TextOriginSource('extra', null)) == 'found in extra'
    }

    @TempDir
    Path tmp

    private String foundIn(PropertySource<?> source) {
        def environment = new AbstractEnvironment() {}
        environment.propertySources.addFirst(source)
        def violations = new ConfigViolations(ConfigLevels.application(),
                new ConfigPlaces(FactoryHome.at(tmp.resolve('home')), new ProjectName('widgets'), []))
        OperatorSources.check(environment, violations)
        violations.lines()[0].split(' — ')[1]
    }

    /** A source whose every origin is line 3 (zero-based 2) of {@code resource}, which may be null. */
    static class TextOriginSource extends MapPropertySource implements OriginLookup<String> {
        private final Resource resource

        TextOriginSource(String name, Resource resource) {
            super(name, ['factory.no-such-key': '1'])
            this.resource = resource
        }

        @Override
        Origin getOrigin(String key) {
            new TextResourceOrigin(resource, new TextResourceOrigin.Location(2, 0))
        }
    }
}
