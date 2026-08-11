package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException
import com.github.oinsio.gnomish.adapter.console.ConsoleIO
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import java.util.function.BooleanSupplier
import java.util.function.Supplier
import spock.lang.Specification
import spock.lang.Unroll

/**
 * FR6, UX2 of add-claim-heartbeat (task 6.2): the production {@link ConsoleTakeoverConfirmation} —
 * the pre-claim takeover prompt. UX2 is the operator surface: one confirmed command, holder and
 * last-beat age shown before the "yes". TTY presence and the {@link ConsoleIO} are injected so the seam is
 * unit-testable without a terminal: no TTY answers UNAVAILABLE (the flag-only headless path), a TTY
 * prints the claim facts and reads one line (y/yes confirm, anything else — including EOF — decline).
 */
class ConsoleTakeoverConfirmationSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-7')

    /** A ConsoleIO double that records printed text and replays a scripted answer (or EOF). */
    private static class RecordingConsole implements ConsoleIO {
        final StringBuilder printed = new StringBuilder()
        String answer
        boolean eof

        @Override
        String readLine() {
            if (eof) {
                throw new ConsoleClosedException()
            }
            answer
        }

        @Override
        void print(String text) {
            printed.append(text)
        }
    }

    private static ConsoleTakeoverConfirmation confirmation(boolean tty, RecordingConsole console) {
        new ConsoleTakeoverConfirmation(
                { tty } as BooleanSupplier, { console } as Supplier<ConsoleIO>)
    }

    // FR6: no TTY → UNAVAILABLE, and the console is never even built (a headless run must not touch stdin).
    def "no TTY reports UNAVAILABLE without building the console"() {
        given:
        def built = false
        def seam = new ConsoleTakeoverConfirmation(
                { false } as BooleanSupplier,
                { built = true; new RecordingConsole() } as Supplier<ConsoleIO>)

        when:
        def decision = seam.confirm(REF, 'gnomish-other-x1', '47m')

        then:
        decision == TakeoverConfirmation.Decision.UNAVAILABLE
        !built
    }

    // FR6, UX2: a TTY prompt shows the ref, holder, and last-beat age before reading the answer —
    // the operator sees who held it and how stale it is before saying yes.
    def "a TTY prompt shows the ref, holder, and last-beat age"() {
        given:
        def console = new RecordingConsole(answer: 'y')

        when:
        confirmation(true, console).confirm(REF, 'gnomish-other-x1', '47m')

        then:
        console.printed.toString().contains('PROJ-7')
        console.printed.toString().contains('gnomish-other-x1')
        console.printed.toString().contains('47m')
    }

    @Unroll
    def "a TTY answer #answer maps to #expected"() {
        given:
        def console = new RecordingConsole(answer: answer)

        expect:
        confirmation(true, console).confirm(REF, 'gnomish-other-x1', '47m') == expected

        where:
        answer  | expected
        'y'     | TakeoverConfirmation.Decision.CONFIRMED
        'yes'   | TakeoverConfirmation.Decision.CONFIRMED
        '  Y  ' | TakeoverConfirmation.Decision.CONFIRMED
        'YES'   | TakeoverConfirmation.Decision.CONFIRMED
        'n'     | TakeoverConfirmation.Decision.DECLINED
        ''      | TakeoverConfirmation.Decision.DECLINED
        'nope'  | TakeoverConfirmation.Decision.DECLINED
    }

    // FR6, D9: the production wiring factory returns a real, ready confirmation — its return value
    // must not be null (kills the NullReturnValsMutator on systemTty()). Constructing it only wires
    // the System.console()/SystemConsoleIO method-reference suppliers; neither is invoked here, so
    // this is deterministic even under a headless test JVM — no terminal is touched until confirm()
    // consults the probe.
    def "systemTty wires a non-null production confirmation without touching the terminal"() {
        expect:
        ConsoleTakeoverConfirmation.systemTty() != null
    }

    // FR6: EOF at the prompt (Ctrl-D) is a non-confirming answer, never an escaping exception.
    def "EOF at the TTY prompt declines rather than throwing"() {
        given:
        def console = new RecordingConsole(eof: true)

        when:
        def decision = confirmation(true, console).confirm(REF, 'gnomish-other-x1', '47m')

        then:
        decision == TakeoverConfirmation.Decision.DECLINED
    }

    // FR6, design D9: systemTty() must hand back a real, fully wired confirmation — never null —
    // with both production seams present (PIT: NULL_RETURNS on systemTty). The seams themselves
    // (System.console() probe, stdin/stdout console) stay unexercised: they carry @DoNotMutate as
    // pure System integration wiring, and building the record is side-effect-free.
    def "systemTty wires a non-null production confirmation with both seams present"() {
        when:
        def production = ConsoleTakeoverConfirmation.systemTty()

        then:
        production != null
        production.ttyPresent() != null
        production.consoleFactory() != null
    }
}
