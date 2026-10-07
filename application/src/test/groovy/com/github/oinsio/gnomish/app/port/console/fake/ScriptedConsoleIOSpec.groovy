package com.github.oinsio.gnomish.app.port.console.fake

import com.github.oinsio.gnomish.app.port.console.ConsoleClosedException
import spock.lang.Specification

/**
 * {@link ScriptedConsoleIO} is an output-capturing fake: it records everything printed to it and
 * holds no input, so a read is the simulated EOF (design D4 of make-run-headless).
 */
class ScriptedConsoleIOSpec extends Specification {

    def "FR13: a read is the simulated EOF"() {
        given:
        def io = new ScriptedConsoleIO()

        when:
        io.readLine()

        then:
        thrown(ConsoleClosedException)
    }

    def "records printed output in order"() {
        given:
        def io = new ScriptedConsoleIO()

        when:
        io.print('one')
        io.printMachine('two')

        then:
        io.printed == ['one', 'two']
        io.printedMachine == ['two']
    }
}
