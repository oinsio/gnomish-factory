package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * FR2, FR6, FR8 of extract-agent-round-runner: the vendor-neutral values a dialect hands the
 * agent round runner — the rendered argv, one line's yield, the round's stamped events and the status
 * of its end — hold what they claim and refuse what cannot be a value.
 */
class AgentDialectValuesSpec extends Specification {

    def "FR2: a rendered argv keeps the command line as given and cannot be changed afterwards"() {
        given:
        def given = ['claude', '--print', '']

        when:
        def argv = new RenderedArgv(given)
        given << '--dangerous'

        then: 'the binary first, an empty argument kept, the caller\'s later edit not seen'
        argv.argv() == ['claude', '--print', '']

        when:
        argv.argv() << '--more'

        then:
        thrown(UnsupportedOperationException)
    }

    def "FR2, FR3: a rendered argv carries the stdin the dialect gives it, and none when it gives none"() {
        expect:
        new RenderedArgv(['claude'], 'the prompt').stdin() == 'the prompt'
        new RenderedArgv(['claude']).stdin() == null
        new RenderedArgv(['claude']) == new RenderedArgv(['claude'], null)
    }

    def "FR2: an argv with no binary is refused"() {
        when:
        new RenderedArgv([])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('binary')
    }

    def "FR2, FR6: one line yields the dialect's events and the vendor-neutral progress, copied"() {
        given:
        def events = ['init']
        def progress = [
            new AgentProgressEvent.RoundStarted(UntrustedText.agent('m'))
        ]

        when:
        def line = new ParsedLine<String>(events, progress)
        events << 'late'

        then:
        line.events() == ['init']
        line.progress() == progress
    }

    def "FR2: an unrecognized line yields nothing"() {
        expect:
        ParsedLine.nothing().events().isEmpty()
        ParsedLine.nothing().progress().isEmpty()
    }

    def "FR2: the round's events are a read-only view the runner appends to without a copy"() {
        given:
        def owned = []
        def view = new ParsedEvents<String>(owned)
        def at = Instant.parse('2026-11-20T10:00:00Z')

        when: 'the runner appends after the view was handed out'
        owned << new ParsedEvents.Stamped<String>(at, 'result')

        then: 'the view sees it, stamp and event'
        view.stamped()*.event() == ['result']
        view.stamped()*.at() == [at]

        when: 'a dialect tries to write through the view'
        view.stamped() << new ParsedEvents.Stamped<String>(at, 'forged')

        then:
        thrown(UnsupportedOperationException)
    }

    def "FR2: a round's first line is parsed against no history"() {
        expect:
        ParsedEvents.none().stamped().isEmpty()
    }

    def "FR8: a limit stop names the bound that stopped it"() {
        expect:
        new RoundStatus.LimitReached('maxTurns=30').bound() == 'maxTurns=30'
    }

    def "FR8: a limit stop that names no bound is refused"() {
        when:
        new RoundStatus.LimitReached(' ')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('bound')
    }
}
