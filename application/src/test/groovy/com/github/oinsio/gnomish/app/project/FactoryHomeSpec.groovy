package com.github.oinsio.gnomish.app.project

import java.nio.file.Path
import spock.lang.Specification

/**
 * The factory home {@link FactoryHome} resolves from {@code GNOMISH_HOME} or the user's home, and
 * the host paths at its root.
 *
 * <p>Implements FR1, FR8, FR11 of add-project-registry.
 */
class FactoryHomeSpec extends Specification {

    // FR1: GNOMISH_HOME names the home, whatever the user's home is
    def "GNOMISH_HOME names the factory home"() {
        when:
        def home = FactoryHome.from(lookup(GNOMISH_HOME: '/srv/gnomish', 'user.home': '/home/op'))

        then:
        home.root() == Path.of('/srv/gnomish')
    }

    // FR1: the default home is .gnomish under the user's home
    def "without GNOMISH_HOME the home is .gnomish in the user's home, blank counting as unset"() {
        when:
        def home = FactoryHome.from(lookup(properties))

        then:
        home.root() == Path.of('/home/op/.gnomish')

        where:
        properties << [
            ['user.home': '/home/op'],
            [GNOMISH_HOME: '  ', 'user.home': '/home/op']
        ]
    }

    // FR1: a relative GNOMISH_HOME is made absolute and normalized
    def "a relative GNOMISH_HOME resolves against the working directory"() {
        when:
        def home = FactoryHome.from(lookup(GNOMISH_HOME: 'var/../gnomish'))

        then:
        home.root() == Path.of('gnomish').toAbsolutePath()
    }

    // FR1: no home can be derived with neither value set
    def "with neither GNOMISH_HOME nor user.home the home cannot be built"() {
        when:
        FactoryHome.from(lookup([:]))

        then:
        def e = thrown(IllegalStateException)
        e.message == 'GNOMISH_HOME is not set and user.home is unknown: set GNOMISH_HOME'
    }

    // FR1, FR5, FR8, FR11: the host paths at the root
    def "the host file, host secrets, project-less log file and projects folder sit at the root"() {
        given:
        def home = FactoryHome.at(Path.of('/srv/gnomish'))

        expect:
        home.hostConfig() == Path.of('/srv/gnomish/factory.yaml')
        home.hostSecret('GNOMISH_GITHUB_TOKEN') == Path.of('/srv/gnomish/secrets/GNOMISH_GITHUB_TOKEN')
        home.hostLogFile() == Path.of('/srv/gnomish/logs/factory.log')
        home.projects() == Path.of('/srv/gnomish/projects')
    }

    // FR8: a secret name cannot lead out of the host secrets folder
    def "a secret name that is not one folder name is refused for the host secrets folder"() {
        when:
        FactoryHome.at(Path.of('/srv/gnomish')).hostSecret('/etc/passwd')

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "invalid secret name '/etc/passwd': it must be one folder name, not . or .."
    }

    // FR1: a project's folder is projects/<name> under the same root
    def "a project's layout is its folder under projects"() {
        when:
        def layout = FactoryHome.at(Path.of('/srv/gnomish')).project(new ProjectName('widgets'))

        then:
        layout.dir() == Path.of('/srv/gnomish/projects/widgets')
        layout.name() == new ProjectName('widgets')
    }

    // FR1: one home, compared by its folder
    def "two homes at one folder are equal and print as the folder"() {
        given:
        def home = FactoryHome.at(Path.of('/srv/gnomish'))

        expect:
        home == FactoryHome.at(Path.of('/srv/./gnomish'))
        home.hashCode() == FactoryHome.at(Path.of('/srv/gnomish')).hashCode()
        home != FactoryHome.at(Path.of('/srv/other'))
        home.hashCode() != FactoryHome.at(Path.of('/srv/other')).hashCode()
        home != (Object) '/srv/gnomish'
        home.toString() == '/srv/gnomish'
    }

    private static FactoryHome.PropertyLookup lookup(Map<String, String> values) {
        return { String name -> values[name] } as FactoryHome.PropertyLookup
    }
}
