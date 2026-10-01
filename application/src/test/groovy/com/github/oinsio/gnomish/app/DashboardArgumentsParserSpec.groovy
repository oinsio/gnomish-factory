package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1, FR7 of add-dashboard-page (task 4.1): {@link DashboardArgumentsParser} fills {@code
 * dir} from the registered clone (FR3 of add-project-registry) and defaults {@code --out} to {@code null} (the instance-directory
 * default resolved by {@link DashboardCommand}), mirroring {@link BoardArgumentsParser}'s {@code
 * --dir} idiom, and carries {@code --watch} as a plain flag.
 */
class DashboardArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    def parser = new DashboardArgumentsParser()


    /** The clone the configuration loader resolved: the {@code dir} every parse fills (FR3, D9 of add-project-registry). */
    private static final RegisteredClone CLONE =
    RegisteredCloneFixture.unregistered(Path.of('/tmp/gnomish-home'), Path.of('/tmp/registered-clone'))
    def "FR3: dir is the registered clone's path, --out defaults to null and --watch to false"() {
        when:
        def parsed = parser.parse(args('dashboard'), CLONE)

        then:
        parsed.dir() == CLONE.clonePath()
        parsed.out() == null
        !parsed.watch()
    }

    def "FR3: an explicit --dir is accepted and resolves nothing — the loader already did"() {
        when:
        def parsed = parser.parse(args('dashboard', '--dir=/tmp/project'), CLONE)

        then:
        parsed.dir() == CLONE.clonePath()
    }

    def "parses an explicit --out override"() {
        when:
        def parsed = parser.parse(args('dashboard', '--out=incident.html'), CLONE)

        then:
        parsed.out() == Path.of('incident.html')
    }

    def "parses the --watch flag"() {
        when:
        def parsed = parser.parse(args('dashboard', '--watch'), CLONE)

        then:
        parsed.watch()
    }
}
