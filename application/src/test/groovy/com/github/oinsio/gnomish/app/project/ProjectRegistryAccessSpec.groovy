package com.github.oinsio.gnomish.app.project

import com.github.oinsio.gnomish.app.UsageException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The project registry over directories the operator cannot read (FR2, FR3, UX2): the operator's
 * own directory that cannot be resolved is reported as unreadable, never as an unregistered clone,
 * while a registered clone the operator cannot reach does not stop any other clone from resolving.
 *
 * <p>Implements FR2, FR3, UX2 of add-project-registry.
 */
class ProjectRegistryAccessSpec extends Specification {

    @TempDir
    Path tmp

    FactoryHome home
    Path locked

    def setup() {
        home = FactoryHome.at(tmp.resolve('home'))
        locked = Files.createDirectories(tmp.resolve('locked'))
    }

    def cleanup() {
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString('rwx------'))
    }

    // FR3, UX2: a permission failure is not an unregistered directory
    def "a symlink into a folder the operator cannot search is reported as unreadable"() {
        given:
        def widgets = gitTree(locked.resolve('widgets'))
        ProjectRegistry.scan(home).add(new ProjectName('widgets'), widgets)
        def link = Files.createSymbolicLink(tmp.resolve('w'), widgets)
        lock()

        when:
        ProjectRegistry.scan(home).resolve(link)

        then:
        def e = thrown(UsageException)
        e.message.startsWith("$link cannot be read")
        !e.message.contains('not a registered clone')
    }

    // FR3: one unreachable registered clone does not stop the others from resolving
    def "a registered clone in a folder the operator cannot search leaves the other clones resolvable"() {
        given:
        def hidden = gitTree(locked.resolve('hidden'))
        ProjectRegistry.scan(home).add(new ProjectName('hidden'), hidden)
        def widgets = gitTree(tmp.resolve('src/widgets'))
        ProjectRegistry.scan(home).add(new ProjectName('widgets'), widgets)
        lock()

        expect:
        ProjectRegistry.scan(home).resolve(widgets).project() == new ProjectName('widgets')
    }

    private void lock() {
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString('---------'))
    }

    private static Path gitTree(Path dir) {
        Files.createDirectories(dir.resolve('.git'))
        dir
    }
}
