package com.github.oinsio.gnomish.app

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1 of add-board-command (task 3.2): {@link BoardArgumentsParser} defaults {@code --dir} to the
 * current directory and {@code --limit} to 50, mirroring {@link ServeArgumentsParser}'s {@code
 * --slots} idiom for a positive-only override, and carries {@code --json} as a plain flag.
 */
class BoardArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    def parser = new BoardArgumentsParser()

    def "defaults --dir to the current directory, --json to false, and --limit to 50"() {
        when:
        def parsed = parser.parse(args('board'))

        then:
        parsed.dir() == Path.of('.')
        !parsed.json()
        parsed.limit() == 50
    }

    def "parses an explicit --dir override"() {
        when:
        def parsed = parser.parse(args('board', '--dir=/tmp/project'))

        then:
        parsed.dir() == Path.of('/tmp/project')
    }

    def "parses the --json flag"() {
        when:
        def parsed = parser.parse(args('board', '--json'))

        then:
        parsed.json()
    }

    def "parses an explicit positive --limit override"() {
        when:
        def parsed = parser.parse(args('board', '--limit=5'))

        then:
        parsed.limit() == 5
    }

    def "rejects a zero --limit"() {
        when:
        parser.parse(args('board', '--limit=0'))

        then:
        UsageException ex = thrown()
        ex.message.contains('--limit')
        ex.message.contains('positive')
    }

    def "rejects a negative --limit"() {
        when:
        parser.parse(args('board', '--limit=-3'))

        then:
        UsageException ex = thrown()
        ex.message.contains('--limit')
    }

    def "rejects a non-numeric --limit"() {
        when:
        parser.parse(args('board', '--limit=many'))

        then:
        UsageException ex = thrown()
        ex.message.contains('--limit')
    }
}
