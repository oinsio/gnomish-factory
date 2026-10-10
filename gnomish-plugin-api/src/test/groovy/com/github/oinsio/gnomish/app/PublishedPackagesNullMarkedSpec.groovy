package com.github.oinsio.gnomish.app

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * Every package of the published plugin surface is null-marked in the jar a plugin compiles
 * against. The build's own NullAway treats the whole namespace as annotated, so a missing
 * {@code package-info.java} is invisible to it; a third party's checker sees an unannotated
 * package and reads every contract type of it as of unknown nullness.
 *
 * <p>The check reads this module's sources, not the runtime package: {@code app} is split with
 * {@code :application}, whose own {@code package-info} reaches this test classpath through
 * {@code :test-fixtures} and would answer for a jar that ships none.
 */
class PublishedPackagesNullMarkedSpec extends Specification {

    private static final Path SOURCES = Path.of('src/main/java')

    def "every package of the published surface has a package-info carrying @NullMarked"() {
        given:
        def packages = packageDirectories()

        expect: 'the scan reached the module sources'
        packages.contains(SOURCES.resolve('com/github/oinsio/gnomish/app'))
        packages.contains(SOURCES.resolve('com/github/oinsio/gnomish/app/port/tracker'))

        and:
        packages.findAll { !nullMarked(it) } == []
    }

    // Every directory holding a Java type, sorted so a failure lists the gaps stably.
    private static List<Path> packageDirectories() {
        Files.walk(SOURCES).withCloseable { paths ->
            paths.filter { it.fileName.toString().endsWith('.java') }
            .filter { it.fileName.toString() != 'package-info.java' }
            .map { it.parent }
            .distinct()
            .sorted()
            .toList()
        }
    }

    private static boolean nullMarked(Path directory) {
        def info = directory.resolve('package-info.java')
        Files.exists(info) && Files.readString(info).contains('@NullMarked\npackage ')
    }
}
