package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR2, FR4, D3 of add-factory-serve (task 5.1): {@link ServeArgumentsParser} fills {@code dir}
 * from the registered clone (FR3 of add-project-registry), defaults {@code --slots} to {@code null} (the caller
 * falls back to {@code ServeProperties#slots()}), validates a given {@code --slots} is positive,
 * carries {@code --drain} as a plain flag, and rejects every {@code take}-only flag before the
 * tracker is ever touched — including {@code --interactive}, since {@code serve} is
 * unconditionally non-interactive.
 */
class ServeArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    def parser = new ServeArgumentsParser()


    /** The clone the configuration loader resolved: the {@code dir} every parse fills (FR3, D9 of add-project-registry). */
    private static final RegisteredClone CLONE =
    RegisteredCloneFixture.unregistered(Path.of('/tmp/gnomish-home'), Path.of('/tmp/registered-clone'))
    def "FR3: dir is the registered clone's path when --dir is absent"() {
        when:
        def parsed = parser.parse(args('serve'), CLONE)

        then:
        parsed.dir() == CLONE.clonePath()
        parsed.slots() == null
        !parsed.drain()
    }

    def "FR3: an explicit --dir is accepted and resolves nothing — the loader already did"() {
        when:
        def parsed = parser.parse(args('serve', '--dir=/tmp/project'), CLONE)

        then:
        parsed.dir() == CLONE.clonePath()
    }

    def "parses an explicit positive --slots override"() {
        when:
        def parsed = parser.parse(args('serve', '--slots=4'), CLONE)

        then:
        parsed.slots() == 4
    }

    def "rejects a zero --slots"() {
        when:
        parser.parse(args('serve', '--slots=0'), CLONE)

        then:
        UsageException ex = thrown()
        ex.message.contains('--slots')
        ex.message.contains('positive')
    }

    def "rejects a negative --slots"() {
        when:
        parser.parse(args('serve', '--slots=-1'), CLONE)

        then:
        UsageException ex = thrown()
        ex.message.contains('--slots')
    }

    def "rejects a non-numeric --slots"() {
        when:
        parser.parse(args('serve', '--slots=many'), CLONE)

        then:
        UsageException ex = thrown()
        ex.message.contains('--slots')
    }

    def "parses the --drain flag"() {
        when:
        def parsed = parser.parse(args('serve', '--drain'), CLONE)

        then:
        parsed.drain()
    }

    // FR4: serve is unconditionally non-interactive — not even --interactive is accepted
    def "rejects an inapplicable take-only or run-only flag"() {
        when:
        parser.parse(args('serve', "--$flag".toString()), CLONE)

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
