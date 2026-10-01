package com.github.oinsio.gnomish.config

import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.origin.Origin
import org.springframework.boot.origin.OriginLookup
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.core.env.AbstractEnvironment
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.FileSystemResource
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Every effective {@code factory.*} value with its origin, {@link EffectiveConfiguration} — the
 * rows {@code project show} prints (FR4, NFR-O1): the winning source's value and origin, the value
 * it overrides, the built-in default of an unset key, and each key's level.
 *
 * <p>Implements FR4, NFR-O1 of add-project-registry.
 */
class EffectiveConfigurationSpec extends Specification {

    @TempDir
    Path tmp

    FactoryHome home
    ConfigurableEnvironment environment = new AbstractEnvironment() {}
    ConfigLevels levels = ConfigLevels.application()

    def setup() {
        home = FactoryHome.at(tmp.resolve('home'))
    }

    // FR4, NFR-O1, U5: "where a value came from" — the project file over the host file
    def "a value set in both files shows the project file and line, and the host value it overrides"() {
        given:
        environment.propertySources.addLast(yaml(home.project(new ProjectName('widgets')).config(),
                "clones:\n  widgets: /src/widgets\nfactory:\n  git-network-timeout: 10m\n"))
        environment.propertySources.addLast(yaml(home.hostConfig(), "factory:\n  git-network-timeout: 5m\n"))

        when:
        def row = row('factory.git-network-timeout')

        then:
        row.value() == '10m'
        row.level() == 'any'
        row.origin() == 'projects/widgets/project.yaml:4'
        row.overrides() == '5m from factory.yaml:2'
    }

    // FR4: the command line and JVM system properties are named as such
    def "the command line and system properties are named as origins"() {
        given:
        environment.propertySources.addLast(new SimpleCommandLinePropertySource(['--factory.serve.slots=4'] as String[]))
        environment.propertySources.addLast(new PropertiesPropertySource(
                        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, ['factory.serve.slots': '3'] as Properties))

        when:
        def row = row('factory.serve.slots')

        then:
        row.value() == '4'
        row.origin() == 'command line'
        row.overrides() == '3 from command line (-D)'
    }

    // FR4: a key no source sets shows the default the records bind, levelled
    def "an unset key shows its built-in default"() {
        when:
        def row = row('factory.instance-name')

        then:
        row.value() == 'default'
        row.level() == 'any'
        row.origin() == 'built-in default'
        row.overrides() == null
    }

    // FR4: every declared key appears once, set or not; subtrees included
    def "every declared key appears, a set subtree by its own entries"() {
        given:
        environment.propertySources.addLast(new MapPropertySource('test', [
            'factory.bindings.stages.build': 'host',
            'factory.gitNetworkTimeout' : '1m',
            'unrelated.key' : 'x',
        ]))

        when:
        def keys = EffectiveConfiguration.of(environment, levels, home)*.key()

        then:
        keys == keys.toSorted()
        keys.containsAll([
            'factory.bindings.stages.build',
            'factory.gitNetworkTimeout',
            'factory.check',
            'factory.sandbox.egress-allowlist'
        ])
        !keys.contains('factory.bindings.stages')
        !keys.contains('factory.git-network-timeout')
        !keys.contains('unrelated.key')
        row('factory.bindings.stages.build').level() == 'sandbox-boundary'
        row('factory.bindings.stages.build').origin() == 'test'
        row('factory.check').value() == '{}'
    }

    // FR4, FR12: a key no record defines is shown with an unknown level
    def "a key no record defines has an unknown level"() {
        given:
        environment.propertySources.addLast(new MapPropertySource('test', ['factory.agent-cli-env-passthrough': 'X']))

        expect:
        row('factory.agent-cli-env-passthrough').level() == 'unknown'
    }

    // NFR-O1: a file outside the home is named absolutely; a resource with no file by its description
    def "a file outside the home is named by its absolute path, a non-file resource by its description"() {
        given:
        def outside = tmp.resolve('elsewhere.yaml')
        environment.propertySources.addLast(yaml(outside, "factory:\n  serve:\n    slots: 2\n"))
        environment.propertySources.addLast(new YamlPropertySourceLoader().load('inline',
                new ByteArrayResource("factory:\n  instance-name: blue\n".bytes, 'inline yaml')).first())

        expect:
        row('factory.serve.slots').origin() == "$outside:3"
        row('factory.instance-name').origin() == 'Byte array resource [inline yaml]:2'
    }

    // NFR-O1: a text origin that names no resource still reports its line
    def "a text origin without a resource is reported as an unknown file with its line"() {
        given:
        environment.propertySources.addLast(new NoResourceSource())

        expect:
        row('factory.serve.slots').origin() == '(unknown file):3'
    }

    /** A source whose origins carry a line but no resource. */
    static class NoResourceSource extends MapPropertySource implements OriginLookup<String> {
        NoResourceSource() {
            super('no-resource', ['factory.serve.slots': '2'])
        }

        @Override
        Origin getOrigin(String key) {
            new TextResourceOrigin(null, new TextResourceOrigin.Location(2, 0))
        }
    }

    private EffectiveConfiguration.Row row(String key) {
        EffectiveConfiguration.of(environment, levels, home).find {
            it.key() == key
        }
    }

    private static yaml(Path file, String text) {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        new YamlPropertySourceLoader().load(file.toString(), new FileSystemResource(file)).first()
    }
}
