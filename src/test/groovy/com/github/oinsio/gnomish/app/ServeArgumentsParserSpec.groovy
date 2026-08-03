package com.github.oinsio.gnomish.app

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR2, FR4, D3 of add-factory-serve (task 5.1): {@link ServeArgumentsParser} defaults {@code --dir}
 * exactly like {@link TakeArgumentsParser}, defaults {@code --slots} to {@code null} (the caller
 * falls back to {@code ServeProperties#slots()}), validates a given {@code --slots} is positive,
 * carries {@code --drain} as a plain flag, and rejects every {@code take}-only flag before the
 * tracker is ever touched — including {@code --interactive}, since {@code serve} is
 * unconditionally non-interactive.
 */
class ServeArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    def parser = new ServeArgumentsParser()

    def "defaults --dir to the current directory when absent"() {
        when:
        def parsed = parser.parse(args('serve'))

        then:
        parsed.dir() == Path.of('.')
        parsed.slots() == null
        !parsed.drain()
    }

    def "parses an explicit --dir override"() {
        when:
        def parsed = parser.parse(args('serve', '--dir=/tmp/project'))

        then:
        parsed.dir() == Path.of('/tmp/project')
    }

    def "parses an explicit positive --slots override"() {
        when:
        def parsed = parser.parse(args('serve', '--slots=4'))

        then:
        parsed.slots() == 4
    }

    def "rejects a zero --slots"() {
        when:
        parser.parse(args('serve', '--slots=0'))

        then:
        UsageException ex = thrown()
        ex.message.contains('--slots')
        ex.message.contains('positive')
    }

    def "rejects a negative --slots"() {
        when:
        parser.parse(args('serve', '--slots=-1'))

        then:
        UsageException ex = thrown()
        ex.message.contains('--slots')
    }

    def "rejects a non-numeric --slots"() {
        when:
        parser.parse(args('serve', '--slots=many'))

        then:
        UsageException ex = thrown()
        ex.message.contains('--slots')
    }

    def "parses the --drain flag"() {
        when:
        def parsed = parser.parse(args('serve', '--drain'))

        then:
        parsed.drain()
    }

    // FR4: serve is unconditionally non-interactive — not even --interactive is accepted
    def "rejects an inapplicable take-only or run-only flag"() {
        when:
        parser.parse(args('serve', "--$flag".toString()))

        then:
        UsageException ex = thrown()
        ex.message.contains("--$flag")
        ex.message.contains('gnomish serve')

        where:
        flag << [
            'task',
            'task-file',
            'task-id',
            'from-stage',
            'resume',
            'base',
            'discard-work',
            'takeover',
            'interactive',
            'mode'
        ]
    }
}
