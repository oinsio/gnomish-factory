package com.github.oinsio.gnomish.app

import java.nio.file.Path
import spock.lang.Specification

/**
 * The installation layout {@link FactoryPaths#underHome} derives from one home directory.
 *
 * <p>Implements FR3 of collapse-composition-roots.
 */
class FactoryPathsSpec extends Specification {

    // FR3: the two roots are distinct values under their own names
    def "the worktree root sits under .gnomish/worktrees of the home directory, and the home directory is kept as given"() {
        given:
        def home = Path.of('/home/gnome')

        when:
        def paths = FactoryPaths.underHome(home)

        then:
        paths.worktreesRoot() == Path.of('/home/gnome/.gnomish/worktrees')
        paths.homeDir() == home
    }
}
