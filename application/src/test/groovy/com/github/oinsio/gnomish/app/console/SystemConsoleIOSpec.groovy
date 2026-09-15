package com.github.oinsio.gnomish.app.console

import com.github.oinsio.gnomish.app.port.console.ConsoleClosedException
import spock.lang.Specification

/**
 * FR13: {@link SystemConsoleIO} reads lines from an input stream in order and
 * raises {@link ConsoleClosedException} on EOF instead of hanging or returning
 * null, and writes output faithfully to the given stream.
 *
 * <p>FR5, NFR-S2 of harden-untrusted-text-sinks: the two write paths. The human path
 * renders what a terminal would obey visibly — it is the console owner, the only class in
 * production code that writes to the process streams — while the machine path writes its
 * input byte for byte, because its reader is a parser and JSON already bounds its own
 * metacharacters. The whole-corpus statement of the same property lives one module up, in
 * the end-to-end invariant spec; here each claim is made against the owner alone.
 */
class SystemConsoleIOSpec extends Specification {

    /**
     * Written as code points, never as literal characters: an ESC or a zero-width space pasted
     * into a source file is invisible to a reviewer and lost by the next tool that touches it.
     */
    static String ch(int codePoint) {
        new String(Character.toChars(codePoint))
    }

    static final String ESC = ch(0x1B)

    def "reads lines in order from the underlying stream"() {
        given:
        def input = new ByteArrayInputStream('first\nsecond\n'.bytes)
        def io = new SystemConsoleIO(input, new ByteArrayOutputStream())

        expect:
        io.readLine() == 'first'
        io.readLine() == 'second'
    }

    def "raises ConsoleClosedException on EOF instead of hanging or returning null"() {
        given:
        def input = new ByteArrayInputStream(''.bytes)
        def io = new SystemConsoleIO(input, new ByteArrayOutputStream())

        when:
        io.readLine()

        then:
        thrown(ConsoleClosedException)
    }

    def "raises ConsoleClosedException once buffered lines are exhausted"() {
        given:
        def input = new ByteArrayInputStream('only\n'.bytes)
        def io = new SystemConsoleIO(input, new ByteArrayOutputStream())

        when:
        io.readLine()
        io.readLine()

        then:
        thrown(ConsoleClosedException)
    }

    def "writes output faithfully to the underlying stream"() {
        given:
        def output = new ByteArrayOutputStream()
        def io = new SystemConsoleIO(new ByteArrayInputStream(''.bytes), output)

        when:
        io.print('hello there')

        then:
        output.toString('UTF-8') == 'hello there'
    }

    def "FR5: the human path shows what a terminal would obey rather than passing it on — #label"() {
        given:
        def output = new ByteArrayOutputStream()
        def io = new SystemConsoleIO(new ByteArrayInputStream(''.bytes), output)

        when:
        io.print(text)

        then:
        output.toString('UTF-8') == rendered

        where:
        label | text || rendered
        'ANSI colour' | "${ESC}[31mred${ESC}[0m" || '^[[31mred^[[0m'
        'OSC 52 clipboard write' | "title${ESC}]52;c;cGF5bG9hZA==${ch(0x07)}" || 'title^[]52;c;cGF5bG9hZA==^G'
        'carriage return' | "done${ch(0x0D)}fake" || 'done\\rfake'
        'C1 CSI' | "a${ch(0x9B)}31mb" || 'a\\u009B31mb'
        'bidi override' | "a${ch(0x202E)}b" || 'a\\u202Eb'
        'zero-width space' | "a${ch(0x200B)}b" || 'a\\u200Bb'
        'tag character' | "a${ch(0xE0001)}b" || 'a\\U000E0001b'
        'plain text' | 'fatal: not a git repository' || 'fatal: not a git repository'
    }

    def "NFR-S2: the human path keeps the line structure of a multi-line report"() {
        given:
        def output = new ByteArrayOutputStream()
        def io = new SystemConsoleIO(new ByteArrayInputStream(''.bytes), output)
        def report = (1..40).collect { "line ${it}" }.join('\n')

        when:
        io.print(report)

        then: 'a forty-line escalation report arrives as forty lines, unchanged'
        output.toString('UTF-8') == report
    }

    def "NFR-S2: the human path leaves nothing a terminal would execute — #label"() {
        given:
        def output = new ByteArrayOutputStream()
        def io = new SystemConsoleIO(new ByteArrayInputStream(''.bytes), output)

        when:
        io.print(text)

        then:
        output.toString('UTF-8').codePoints().noneMatch { int c ->
            c == 0x1B || c == 0x0D || (c >= 0x7F && c <= 0x9F) ||
            (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)
        }

        where:
        label | text
        'ANSI colour' | "${ESC}[31mred${ESC}[0m"
        'OSC without terminator' | "${ESC}]0;runs to the end"
        'C1 CSI' | "a${ch(0x9B)}31mb"
        'DEL' | "a${ch(0x7F)}b"
        'bidi RLO' | "a${ch(0x202E)}b"
        'carriage return' | "a${ch(0x0D)}b"
    }

    def "FR5: the machine path writes its input byte for byte — #label"() {
        given:
        def output = new ByteArrayOutputStream()
        def io = new SystemConsoleIO(new ByteArrayInputStream(''.bytes), output)

        when:
        io.printMachine(text)

        then: 'a parser, not a terminal, reads this — the JSON encoding already bounds it'
        output.toString('UTF-8') == text

        where:
        label | text
        'plain JSON' | '{"task":"GF-17","state":"RUNNING"}'
        'JSON-escaped escape' | '{"title":"\\u001b[31mred"}'
        'raw escape in a value' | "{\"title\":\"${ESC}[31mred\"}"
        'multi-line JSON' | '{\n  "task": "GF-17"\n}'
    }
}
