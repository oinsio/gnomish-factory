package com.github.oinsio.gnomish.testfixtures.standin

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR24 of supervise-daemon-loops-and-embed-dashboard (design D23, ADR 0015): {@link StandIn}, the
 * one owner of stand-in binaries in test sources, hands out committed presets and creates nothing
 * but symbolic links to them — never an executable file.
 */
class StandInSpec extends Specification {

    @TempDir
    Path tempDir

    def "FR24: a preset is handed out by its committed link, its answers by reference"() {
        expect:
        StandIn.git('action-answer') == StandIn.library().resolve('links/action-answer')
        StandIn.data('common#url') == 'https://example.invalid/repo.git\n'
        StandIn.data('common#empty-line') == '\n'
    }

    def "FR24: a preset that records, reads per-run files or takes its link's name is not handed out by its committed link"() {
        when:
        StandIn.git(preset)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('StandIn.recording or StandIn.link')

        where:
        preset << [
            'action-record',
            'action-per-run',
            'action-steps'
        ]
    }

    def "FR24: an unknown preset is refused with its name"() {
        when:
        StandIn.git('no-such-preset')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("no stand-in preset 'no-such-preset'")
    }

    def "FR24: a preset taken as the wrong kind of binary is refused with its kind"() {
        when:
        StandIn.docker('action-answer')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('stands in for action, not docker')
    }

    def "FR24: a data file the library lacks is refused with its name"() {
        when:
        StandIn.data('common#nope')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("has no answer 'common#nope'")
    }

    def "FR24: recording twice in one directory is two links to the preset, so two logs"() {
        when:
        Path first = StandIn.recording(tempDir, 'action-record')
        Path second = StandIn.recording(tempDir, 'action-record')

        then: 'links only — no executable file was written'
        first.fileName.toString() == 'action-record-1'
        second.fileName.toString() == 'action-record-2'
        [first, second].every {
            Files.isSymbolicLink(it) && Files.readSymbolicLink(it) == StandIn.preset('action-record')
        }
        Files.list(tempDir).withCloseable { it.count() } == 2

        and: 'each has its own log beside it'
        StandIn.log(first) == tempDir.resolve('action-record-1.log')
        StandIn.log(second) == tempDir.resolve('action-record-2.log')
    }

    def "FR24: a link at an exact path takes the name git runs a hook by"() {
        given:
        Path hooks = Files.createDirectories(tempDir.resolve('hooks'))

        when:
        Path hook = StandIn.link(hooks.resolve('pre-commit'), 'action-record')

        then:
        Files.isSymbolicLink(hook)
        Files.readSymbolicLink(hook) == StandIn.preset('action-record')
    }

    def "FR24: the per-run file names are derived, not created"() {
        given:
        Path link = tempDir.resolve('action-record-1')

        expect:
        StandIn.beside(link, 'ls-remote.out') == tempDir.resolve('action-record-1.ls-remote.out')
        StandInLog.blocks(link) == []
        Files.list(tempDir).withCloseable { it.count() } == 0
    }
}
