package com.github.oinsio.gnomish.adapter.workspace

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR6, FR7: DirectoryWorkspace exposes the workspace root path that
 * check runners and console adapters need, without the domain ever knowing
 * a filesystem is involved (design D1). Construction fails fast (D3) when
 * the given path is not a pre-existing directory — the workspace is the
 * operator's, and the runner writes zero bytes into it.
 */
class DirectoryWorkspaceSpec extends Specification {

    @TempDir
    Path tempDir

    def "exposes the root path for a valid existing directory"() {
        given:
        Path root = tempDir

        when:
        def workspace = new DirectoryWorkspace(root)

        then:
        workspace.root() == root
    }

    def "fails fast when the path does not exist"() {
        given:
        Path missing = tempDir.resolve("does-not-exist")

        when:
        new DirectoryWorkspace(missing)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(missing.toString())
    }

    def "fails fast when the path is a file, not a directory"() {
        given:
        Path file = tempDir.resolve("not-a-directory.txt")
        Files.writeString(file, "content")

        when:
        new DirectoryWorkspace(file)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(file.toString())
    }
}
