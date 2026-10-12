package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * F119 of tighten-spec-contract (design D12): the composition root's host workspace realization
 * opens a {@link DirectoryWorkspace} over the directory it is given, and refuses one that does not
 * exist — the behavior the use cases relied on when they built the adapter themselves.
 */
class DirectoryWorkspaceFactorySpec extends Specification {

    @TempDir
    Path root

    def "F119: opens a directory workspace rooted at the given directory"() {
        when:
        def workspace = new DirectoryWorkspaceFactory().at(root)

        then:
        workspace instanceof DirectoryWorkspace
        (workspace as DirectoryWorkspace).root() == root
    }

    def "F119: a directory that does not exist is refused"() {
        when:
        new DirectoryWorkspaceFactory().at(root.resolve('absent'))

        then:
        thrown(IllegalArgumentException)
    }
}
