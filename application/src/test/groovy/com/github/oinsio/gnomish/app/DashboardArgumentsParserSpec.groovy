package com.github.oinsio.gnomish.app

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1, FR7 of add-dashboard-page (task 4.1): {@link DashboardArgumentsParser} defaults {@code
 * --dir} to the current directory and {@code --out} to {@code null} (the instance-directory
 * default resolved by {@link DashboardCommand}), mirroring {@link BoardArgumentsParser}'s {@code
 * --dir} idiom, and carries {@code --watch} as a plain flag.
 */
class DashboardArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    def parser = new DashboardArgumentsParser()

    def "defaults --dir to the current directory, --out to null, and --watch to false"() {
        when:
        def parsed = parser.parse(args('dashboard'))

        then:
        parsed.dir() == Path.of('.')
        parsed.out() == null
        !parsed.watch()
    }

    def "parses an explicit --dir override"() {
        when:
        def parsed = parser.parse(args('dashboard', '--dir=/tmp/project'))

        then:
        parsed.dir() == Path.of('/tmp/project')
    }

    def "parses an explicit --out override"() {
        when:
        def parsed = parser.parse(args('dashboard', '--out=incident.html'))

        then:
        parsed.out() == Path.of('incident.html')
    }

    def "parses the --watch flag"() {
        when:
        def parsed = parser.parse(args('dashboard', '--watch'))

        then:
        parsed.watch()
    }
}
