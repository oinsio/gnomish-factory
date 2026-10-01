package com.github.oinsio.gnomish.app.project

import java.nio.file.Path
import spock.lang.Shared
import spock.lang.Specification

/**
 * The operator paths inside one project's folder, computed by {@link ProjectLayout}.
 *
 * <p>Implements FR1, FR8, FR9, FR10, FR11 of add-project-registry.
 */
class ProjectLayoutSpec extends Specification {

    @Shared
    def layout = FactoryHome.at(Path.of('/srv/gnomish')).project(new ProjectName('widgets'))

    // FR1, FR8: the project file and the project's secrets
    def "the project file and secrets folder sit in the project folder"() {
        expect:
        layout.config() == Path.of('/srv/gnomish/projects/widgets/project.yaml')
        layout.secret('GNOMISH_GITHUB_TOKEN') == Path.of('/srv/gnomish/projects/widgets/secrets/GNOMISH_GITHUB_TOKEN')
    }

    // FR11: one log file per instance of the project
    def "an instance logs to logs/<instance>.log"() {
        expect:
        layout.logFile('default') == Path.of('/srv/gnomish/projects/widgets/logs/default.log')
    }

    // FR10: one serve folder per instance of the project
    def "an instance's serve files live in serve/<instance>"() {
        expect:
        layout.serveDir('nightly') == Path.of('/srv/gnomish/projects/widgets/serve/nightly')
    }

    // FR9: each clone keeps its own worktree folder
    def "a clone's worktrees live in worktrees/<clone>"() {
        expect:
        layout.worktrees(new CloneName('widgets-demo')) ==
                Path.of('/srv/gnomish/projects/widgets/worktrees/widgets-demo')
    }

    // FR1, FR8: an instance or secret name cannot lead out of the project folder
    def "a #what that is not one folder name is refused for #use"() {
        when:
        path.call()

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "invalid $what '../other': it must be one folder name, not . or .."

        where:
        use | what | path
        'logs' | 'instance name' | { layout.logFile('../other') }
        'serve' | 'instance name' | { layout.serveDir('../other') }
        'secrets' | 'secret name' | { layout.secret('../other') }
    }

    // FR1: one project folder, one layout
    def "two layouts of one folder are equal and print as the folder"() {
        given:
        def home = FactoryHome.at(Path.of('/srv/gnomish'))

        expect:
        layout == home.project(new ProjectName('widgets'))
        layout.hashCode() == home.project(new ProjectName('widgets')).hashCode()
        layout != home.project(new ProjectName('gadgets'))
        layout.hashCode() != home.project(new ProjectName('gadgets')).hashCode()
        layout != FactoryHome.at(Path.of('/srv/other')).project(new ProjectName('widgets'))
        (Object) layout != '/srv/gnomish/projects/widgets'
        layout.toString() == '/srv/gnomish/projects/widgets'
    }
}
