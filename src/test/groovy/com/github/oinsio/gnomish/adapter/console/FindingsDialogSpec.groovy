package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.adapter.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.domain.engine.Finding
import spock.lang.Specification

/**
 * FR4, FR5: {@link FindingsDialog} is the shared findings-entry dialog the
 * interactive {@code ExternalCheckClient} (task 5.3) and {@code JudgeVoter}
 * (task 5.4) call when the operator reports a failing verdict — one finding
 * message per line, empty line ends collection.
 */
class FindingsDialogSpec extends Specification {

    def "collects a single finding message"() {
        given:
        def io = new ScriptedConsoleIO(['the tests are red', ''])
        def console = new DialogConsole(io, { json -> 'unused' })

        expect:
        new FindingsDialog().collect(console) == [
            new Finding('the tests are red', null, null)
        ]
    }

    def "collects multiple findings in order"() {
        given:
        def io = new ScriptedConsoleIO([
            'first problem',
            'second problem',
            'third problem',
            ''
        ])
        def console = new DialogConsole(io, { json -> 'unused' })

        expect:
        new FindingsDialog().collect(console) == [
            new Finding('first problem', null, null),
            new Finding('second problem', null, null),
            new Finding('third problem', null, null)
        ]
    }

    def "empty line as the very first input ends collection with zero findings"() {
        given:
        def io = new ScriptedConsoleIO([''])
        def console = new DialogConsole(io, { json -> 'unused' })

        expect:
        new FindingsDialog().collect(console) == []
    }

    def "status intercepted mid-collection re-prompts without ending collection or becoming a finding"() {
        given:
        def io = new ScriptedConsoleIO([
            'first problem',
            'status',
            'second problem',
            ''
        ])
        def console = new DialogConsole(io, { json -> 'text-report' })

        expect:
        new FindingsDialog().collect(console) == [
            new Finding('first problem', null, null),
            new Finding('second problem', null, null)
        ]
        io.printed.contains('text-report')
    }

    def "propagates EOF and latches the exhausted flag on the console"() {
        given:
        def io = new ScriptedConsoleIO(['first problem'])
        def console = new DialogConsole(io, { json -> 'unused' })

        when:
        new FindingsDialog().collect(console)

        then:
        thrown(ConsoleClosedException)
        console.inputExhausted()
    }
}
