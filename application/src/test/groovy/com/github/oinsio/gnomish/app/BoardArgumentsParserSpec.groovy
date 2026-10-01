package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1 of add-board-command (task 3.2): {@link BoardArgumentsParser} fills {@code dir} from the
 * registered clone (FR3 of add-project-registry) and defaults {@code --limit} to 50, mirroring {@link ServeArgumentsParser}'s {@code
 * --slots} idiom for a positive-only override, and carries {@code --json} as a plain flag.
 */
class BoardArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    def parser = new BoardArgumentsParser()


    /** The clone the configuration loader resolved: the {@code dir} every parse fills (FR3, D9 of add-project-registry). */
    private static final RegisteredClone CLONE =
    RegisteredCloneFixture.unregistered(Path.of('/tmp/gnomish-home'), Path.of('/tmp/registered-clone'))
    def "FR3: dir is the registered clone's path, --json defaults to false and --limit to 50"() {
        when:
        def parsed = parser.parse(args('board'), CLONE)

        then:
        parsed.dir() == CLONE.clonePath()
        !parsed.json()
        parsed.limit() == 50
    }

    def "FR3: an explicit --dir is accepted and resolves nothing — the loader already did"() {
        when:
        def parsed = parser.parse(args('board', '--dir=/tmp/project'), CLONE)

        then:
        parsed.dir() == CLONE.clonePath()
    }

    def "parses the --json flag"() {
        when:
        def parsed = parser.parse(args('board', '--json'), CLONE)

        then:
        parsed.json()
    }

    def "parses an explicit positive --limit override"() {
        when:
        def parsed = parser.parse(args('board', '--limit=5'), CLONE)

        then:
        parsed.limit() == 5
    }

    def "rejects a zero --limit"() {
        when:
        parser.parse(args('board', '--limit=0'), CLONE)

        then:
        UsageException ex = thrown()
        ex.message.contains('--limit')
        ex.message.contains('positive')
    }

    def "rejects a negative --limit"() {
        when:
        parser.parse(args('board', '--limit=-3'), CLONE)

        then:
        UsageException ex = thrown()
        ex.message.contains('--limit')
    }

    def "rejects a non-numeric --limit"() {
        when:
        parser.parse(args('board', '--limit=many'), CLONE)

        then:
        UsageException ex = thrown()
        ex.message.contains('--limit')
    }
}
