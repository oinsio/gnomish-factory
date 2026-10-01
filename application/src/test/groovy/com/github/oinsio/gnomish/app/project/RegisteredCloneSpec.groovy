package com.github.oinsio.gnomish.app.project

import java.nio.file.Path
import spock.lang.Shared
import spock.lang.Specification

/**
 * The registered clone {@link RegisteredClone}: its own worktree folder, and the refusal of a
 * relative path or a layout of another project.
 *
 * <p>Implements FR3, FR9, NFR-R2 of add-project-registry.
 */
class RegisteredCloneSpec extends Specification {

    @Shared
    def home = FactoryHome.at(Path.of('/srv/gnomish'))

    @Shared
    def widgets = new ProjectName('widgets')

    // FR9, NFR-R2: each clone of a project has its own worktree folder
    def "two clones of one project keep separate worktree folders"() {
        given:
        def first = new RegisteredClone(widgets, new CloneName('widgets'), Path.of('/src/widgets'), home.project(widgets))
        def second = new RegisteredClone(widgets, new CloneName('widgets-demo'), Path.of('/src/widgets-demo'), home.project(widgets))

        expect:
        first.worktrees() == Path.of('/srv/gnomish/projects/widgets/worktrees/widgets')
        second.worktrees() == Path.of('/srv/gnomish/projects/widgets/worktrees/widgets-demo')
    }

    // FR3: a registered path is absolute
    def "a relative clone path is refused"() {
        when:
        new RegisteredClone(widgets, new CloneName('widgets'), Path.of('src/widgets'), home.project(widgets))

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'clone path must be absolute: src/widgets'
    }

    // FR3: the layout belongs to the clone's project
    def "a layout of another project is refused"() {
        when:
        new RegisteredClone(widgets, new CloneName('widgets'), Path.of('/src/widgets'), home.project(new ProjectName('gadgets')))

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "layout of project 'gadgets' given for project 'widgets'"
    }
}
