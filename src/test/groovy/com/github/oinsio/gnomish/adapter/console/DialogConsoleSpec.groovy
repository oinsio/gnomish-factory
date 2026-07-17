package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.adapter.console.fake.RecordingActivityTracker
import com.github.oinsio.gnomish.adapter.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.status.Activity
import java.time.Instant
import spock.lang.Specification

/**
 * FR10, FR13, UX1: {@link DialogConsole} is the single input choke point
 * (design D1) — it intercepts {@code status} / {@code status --json} below
 * every interactive adapter, latches the input-exhausted flag on EOF, and
 * offers a re-prompting helper for fixed-answer questions.
 */
class DialogConsoleSpec extends Specification {

    def "passes plain non-meta input through untouched"() {
        given:
        def io = new ScriptedConsoleIO(['hello'])
        def console = new DialogConsole(io, { json -> 'unused' })

        expect:
        console.prompt('question? ') == 'hello'
        io.printed == ['question? ']
    }

    def "intercepts status, renders text, and re-prompts for the original question"() {
        given:
        def io = new ScriptedConsoleIO(['status', 'answer'])
        def console = new DialogConsole(io, { json -> json ? 'json-report' : 'text-report' })

        expect:
        console.prompt('question? ') == 'answer'
        io.printed == [
            'question? ',
            'text-report',
            'question? '
        ]
    }

    def "intercepts status --json, renders json, and re-prompts for the original question"() {
        given:
        def io = new ScriptedConsoleIO(['status --json', 'answer'])
        def console = new DialogConsole(io, { json -> json ? 'json-report' : 'text-report' })

        expect:
        console.prompt('question? ') == 'answer'
        io.printed == [
            'question? ',
            'json-report',
            'question? '
        ]
    }

    def "handles several status interceptions before a real answer"() {
        given:
        def io = new ScriptedConsoleIO([
            'status',
            'status --json',
            'answer'
        ])
        def console = new DialogConsole(io, { json -> json ? 'json-report' : 'text-report' })

        expect:
        console.prompt('question? ') == 'answer'
        io.printed == [
            'question? ',
            'text-report',
            'question? ',
            'json-report',
            'question? '
        ]
    }

    def "is not exhausted before any EOF"() {
        given:
        def console = new DialogConsole(new ScriptedConsoleIO(['line']), { json -> 'unused' })

        expect:
        !console.inputExhausted()
    }

    def "latches the input-exhausted flag and rethrows on EOF"() {
        given:
        def console = new DialogConsole(new ScriptedConsoleIO([]), { json -> 'unused' })

        when:
        console.prompt('question? ')

        then:
        thrown(ConsoleClosedException)
        console.inputExhausted()
    }

    def "latches exhausted flag on EOF even mid status-loop"() {
        given:
        def io = new ScriptedConsoleIO(['status'])
        def console = new DialogConsole(io, { json -> 'text-report' })

        when:
        console.prompt('question? ')

        then:
        thrown(ConsoleClosedException)
        console.inputExhausted()
        io.printed == [
            'question? ',
            'text-report',
            'question? '
        ]
    }

    def "ask returns the first accepted answer"() {
        given:
        def io = new ScriptedConsoleIO(['pass'])
        def console = new DialogConsole(io, { json -> 'unused' })

        expect:
        console.ask('pass/fail/running? ', ['pass', 'fail', 'running']) == 'pass'
    }

    def "ask re-prompts listing accepted answers on an unrecognized line"() {
        given:
        def io = new ScriptedConsoleIO(['pss', 'pass'])
        def console = new DialogConsole(io, { json -> 'unused' })

        expect:
        console.ask('pass/fail/running? ', ['pass', 'fail', 'running']) == 'pass'
        io.printed.any { it.contains('pass') && it.contains('fail') && it.contains('running') }
    }

    def "ask still intercepts status before checking accepted answers"() {
        given:
        def io = new ScriptedConsoleIO(['status', 'pass'])
        def console = new DialogConsole(io, { json -> 'text-report' })

        expect:
        console.ask('pass/fail/running? ', ['pass', 'fail', 'running']) == 'pass'
        io.printed.contains('text-report')
    }

    def "ask propagates EOF and latches the exhausted flag"() {
        given:
        def console = new DialogConsole(new ScriptedConsoleIO([]), { json -> 'unused' })

        when:
        console.ask('pass/fail/running? ', ['pass', 'fail', 'running'])

        then:
        thrown(ConsoleClosedException)
        console.inputExhausted()
    }

    def "prompt marks awaitingInput before the blocking read and restores the prior activity after"() {
        given:
        def executing = new Activity.Executing(Instant.EPOCH)
        def tracker = new RecordingActivityTracker(executing)
        def console = new DialogConsole(new ScriptedConsoleIO(['answer']), { json -> 'unused' }, tracker)

        expect:
        console.prompt('question? ') == 'answer'
        tracker.markCount == 1
        tracker.restoredTo == [executing]
        tracker.current() == executing
        tracker.prompts == ['question? ']
    }

    def "prompt restores the prior activity even when the read hits EOF"() {
        given:
        def verifying = new Activity.Executing(Instant.EPOCH)
        def tracker = new RecordingActivityTracker(verifying)
        def console = new DialogConsole(new ScriptedConsoleIO([]), { json -> 'unused' }, tracker)

        when:
        console.prompt('question? ')

        then:
        thrown(ConsoleClosedException)
        tracker.markCount == 1
        tracker.restoredTo == [verifying]
        tracker.current() == verifying
    }

    def "prompt marks and restores once per status interception, not just once per prompt call"() {
        given:
        def tracker = new RecordingActivityTracker()
        def io = new ScriptedConsoleIO(['status', 'answer'])
        def console = new DialogConsole(io, { json -> 'text-report' }, tracker)

        expect:
        console.prompt('question? ') == 'answer'
        tracker.markCount == 2
        tracker.restoredTo == [null, null]
    }

    def "ask marks and restores awaitingInput without duplicating prompt's logic"() {
        given:
        def tracker = new RecordingActivityTracker()
        def io = new ScriptedConsoleIO(['pss', 'pass'])
        def console = new DialogConsole(io, { json -> 'unused' }, tracker)

        expect:
        console.ask('pass/fail/running? ', ['pass', 'fail', 'running']) == 'pass'
        tracker.markCount == 2
        tracker.restoredTo == [null, null]
    }

    def "the two-arg constructor defaults to a no-op activity tracker"() {
        given:
        def console = new DialogConsole(new ScriptedConsoleIO(['answer']), { json -> 'unused' })

        expect:
        console.prompt('question? ') == 'answer'
    }
}
