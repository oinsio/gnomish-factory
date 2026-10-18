package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.charset.StandardCharsets
import java.time.Duration
import spock.lang.Specification

/**
 * FR13 of tighten-spec-contract (design D13, amended 2026-10-13): the factory's closing commit and
 * its register land in one {@code git update-ref --stdin} transaction, so the capture-shaped runner
 * hands a command its whole input — as a file the launch owner writes (design D20 of
 * fix-terminal-receipt-convergence), not through a pipe — and a command that stops reading early is
 * judged by its exit.
 *
 * <p>A command given no input sees end-of-input at once (task 9.17 of tighten-spec-contract): a git
 * command that falls back to reading standard input — {@code commit-tree} with an empty message —
 * would otherwise wait on a pipe nobody writes to until its deadline, or forever without one.
 */
class CaptureRunnerInputSpec extends Specification {

    def "FR13: the input reaches the command whole and the stream is closed behind it"() {
        given: 'cat, which echoes its input and exits only at end-of-input'
        ProcessBuilder builder = new ProcessBuilder('cat')
        TestChildEnvironment.cleared(builder)

        when:
        Captured captured = new CaptureRunner().run(
                builder, Duration.ofSeconds(30), 'start\nprepare\ncommit\n'.getBytes(StandardCharsets.UTF_8))

        then:
        captured.termination() == Termination.EXITED
        captured.exitCode() == 0
        captured.stdout() == 'start\nprepare\ncommit\n'
    }

    def "FR13: a command that exits without reading is judged by its exit"() {
        given: 'a command that reads nothing, and more input than any pipe buffer holds'
        ProcessBuilder builder = new ProcessBuilder('sh', '-c', 'exit 3')
        TestChildEnvironment.cleared(builder)
        byte[] input = new byte[4 * 1024 * 1024]

        when:
        Captured captured = new CaptureRunner().run(builder, Duration.ofSeconds(30), input)

        then:
        captured.termination() == Termination.EXITED
        captured.exitCode() == 3
    }

    def "FR13: a command given no input sees end-of-input at once rather than waiting out its deadline"() {
        given: 'cat, which exits only at end-of-input, and no input to hand it'
        ProcessBuilder builder = new ProcessBuilder('cat')
        TestChildEnvironment.cleared(builder)

        when:
        Captured captured = new CaptureRunner().run(builder, Duration.ofSeconds(20))

        then: 'it exits on its own, with nothing read'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 0
        captured.stdout() == ''
    }
}
