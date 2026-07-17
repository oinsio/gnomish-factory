package com.github.oinsio.gnomish.adapter.console.fake

import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException
import spock.lang.Specification

/**
 * FR13: {@link ScriptedConsoleIO} plays back a fixed script of lines in order,
 * then simulates EOF, and records everything printed to it — the fake later
 * tasks' dialog specs script against.
 */
class ScriptedConsoleIOSpec extends Specification {

    def "plays back scripted lines in order"() {
        given:
        def io = new ScriptedConsoleIO(['first', 'second'])

        expect:
        io.readLine() == 'first'
        io.readLine() == 'second'
    }

    def "raises ConsoleClosedException once the script is exhausted"() {
        given:
        def io = new ScriptedConsoleIO(['only'])

        when:
        io.readLine()
        io.readLine()

        then:
        thrown(ConsoleClosedException)
    }

    def "raises ConsoleClosedException immediately for an empty script"() {
        given:
        def io = new ScriptedConsoleIO([])

        when:
        io.readLine()

        then:
        thrown(ConsoleClosedException)
    }

    def "records printed output in order"() {
        given:
        def io = new ScriptedConsoleIO([])

        when:
        io.print('one')
        io.print('two')

        then:
        io.printed == ['one', 'two']
    }
}
