package com.github.oinsio.gnomish.app.console

import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import spock.lang.Specification

/**
 * {@link DialogConsole} is the runner's output owner with no read side (design D4 of
 * make-run-headless): it carries the two write paths of the wrapped {@code ConsoleIO} and
 * nothing else.
 */
class DialogConsoleSpec extends Specification {

    // FR5 of harden-untrusted-text-sinks: the wrapper carries both write paths, so a dialog
    // holding it never has to reach past it for the machine-readable one — and the path a caller
    // chose is the path the console owner is asked for, not one the wrapper decides.
    def "carries both write paths through to the wrapped console"() {
        given:
        def io = new ScriptedConsoleIO()
        def console = new DialogConsole(io)

        when:
        console.print('a briefing for the operator')
        console.printMachine('{"task":"GNOME-17"}')

        then:
        io.printed == [
            'a briefing for the operator',
            '{"task":"GNOME-17"}'
        ]

        and: 'and only the second went the verbatim way'
        io.printedMachine == ['{"task":"GNOME-17"}']
    }
}
