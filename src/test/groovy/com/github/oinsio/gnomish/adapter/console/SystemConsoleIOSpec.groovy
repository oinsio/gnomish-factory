package com.github.oinsio.gnomish.adapter.console

import spock.lang.Specification

/**
 * FR13: {@link SystemConsoleIO} reads lines from an input stream in order and
 * raises {@link ConsoleClosedException} on EOF instead of hanging or returning
 * null, and writes output faithfully to the given stream.
 */
class SystemConsoleIOSpec extends Specification {

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
}
