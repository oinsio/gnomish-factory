package com.github.oinsio.gnomish.app.project

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link FactoryHome#legacyFolders()} finds the pre-registry layout's leftovers at the home's root
 * and names where each kind of state lives now, without counting the current layout's own entries.
 *
 * <p>Implements FR1 of add-doctor-command.
 */
class FactoryHomeLegacyFoldersSpec extends Specification {

    @TempDir
    Path tmp

    // FR1: a home in the registry layout has no leftovers, its own logs/ and secrets/ included
    def "FR1: a home holding only the registry layout reports no leftovers"() {
        given:
        def home = FactoryHome.at(tmp)
        write(home.hostConfig())
        write(home.hostLogFile())
        write(home.hostSecret('GNOMISH_GITHUB_TOKEN'))
        Files.createDirectories(home.project(new ProjectName('widgets')).dir())

        expect:
        home.legacyFolders() == []
    }

    // FR1: a home folder that does not exist yet has nothing left over
    def "FR1: a home folder that does not exist reports no leftovers"() {
        expect:
        FactoryHome.at(tmp.resolve('absent')).legacyFolders() == []
    }

    // FR1: every old root-level entry is reported, in order, with where its state lives now
    def "FR1: the old log file, serve and worktrees folders and per-repository secrets folders are reported"() {
        given:
        def home = FactoryHome.at(tmp)
        write(tmp.resolve('logs/gnomish.log'))
        Files.createDirectories(tmp.resolve('serve/default'))
        Files.createDirectories(tmp.resolve('worktrees'))
        Files.createDirectories(tmp.resolve('secrets/widgets'))
        Files.createDirectories(tmp.resolve('secrets/gizmos'))
        write(home.hostSecret('GNOMISH_GITHUB_TOKEN'))

        expect:
        home.legacyFolders() == [
            new LegacyEntry(tmp.resolve('logs/gnomish.log'), 'projects/<name>/logs/<instance>.log'),
            new LegacyEntry(tmp.resolve('serve'), 'projects/<name>/serve/<instance>/'),
            new LegacyEntry(tmp.resolve('worktrees'), 'projects/<name>/worktrees/<clone>/'),
            new LegacyEntry(tmp.resolve('secrets/gizmos'),
            'one file per secret in projects/<name>/secrets/ or secrets/'),
            new LegacyEntry(tmp.resolve('secrets/widgets'),
            'one file per secret in projects/<name>/secrets/ or secrets/'),
        ]
    }

    // FR1: a leftover link is reported as the leftover itself, not by what it points at
    def "FR1: a dangling link left in place of an old folder is still a leftover"() {
        given:
        Files.createSymbolicLink(tmp.resolve('serve'), tmp.resolve('gone'))

        expect:
        FactoryHome.at(tmp).legacyFolders()*.path() == [tmp.resolve('serve')]
    }

    // FR1: a secrets folder that cannot be listed is a failure, never a silent "no leftovers"
    def "FR1: an unreadable secrets folder fails the listing, naming the folder"() {
        given:
        def secrets = Files.createDirectories(tmp.resolve('secrets'))
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString('---------'))

        when:
        FactoryHome.at(tmp).legacyFolders()

        then:
        def e = thrown(UncheckedIOException)
        e.message == "cannot list ${secrets}".toString()

        cleanup:
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString('rwx------'))
    }

    // FR1: the list is a snapshot the caller cannot change
    def "FR1: the leftovers are returned as an unmodifiable list"() {
        given:
        Files.createDirectories(tmp.resolve('worktrees'))

        when:
        FactoryHome.at(tmp).legacyFolders().clear()

        then:
        thrown(UnsupportedOperationException)
    }

    // FR1: a leftover always names its path and its successor
    def "FR1: a leftover without a path or a successor is refused"() {
        when:
        new LegacyEntry(path, successor)

        then:
        def e = thrown(NullPointerException)
        e.message == expected

        where:
        path | successor || expected
        null | 'x' || 'path'
        Path.of('/a') | null || 'successor'
    }

    private static void write(Path file) {
        Files.createDirectories(file.parent)
        Files.writeString(file, 'x')
    }
}
