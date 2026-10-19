package com.github.oinsio.gnomish.sandbox.environment

import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR17, FR19 of fix-terminal-receipt-convergence (design D15 as amended 2026-10-17, D17); D4 of
 * make-pipe-reads-non-parking: the file channel joins its exec pipes with its {@link DockerCli}'s
 * post-exit bound, and a drain a holder keeps fed every tick after the exit is stopped at that bound
 * and judged by the one cut rule every value reader asks — a clean exit with code zero fails the
 * operation rather than handing back a prefix as the file; any other exit keeps its own failure, the
 * cut named in its detail.
 *
 * <p>Each feature hands the channel a {@link FakeProcess} whose exit and pipes the spec controls; the
 * holder is a {@link WritingHolder} on stdout, whose writes ride the drain's own queries, so no order
 * is left to the scheduler. The scripted CLI's supervisor has no kill grace, so a cut costs the
 * bound's margin alone.
 */
class ContainerFileChannelPostExitSpec extends Specification {

    ScriptedFileChannelDockerCli docker = new ScriptedFileChannelDockerCli()

    def cleanup() {
        Thread.interrupted() // never leak an interrupt flag into the next feature
    }

    private ContainerFileChannel channel() {
        new ContainerFileChannel(docker, 'k1', '/gnomish/work', '/gnomish/scratch')
    }

    /** A process that wrote {@code stdout} and {@code stderr} and exited with {@code code}, a holder still writing to stdout. */
    private FakeProcess heldAfterExit(int code, String stdout = '', String stderr = '') {
        def process = FakeProcess.running()
        if (stdout) {
            process.writeStdout(stdout)
        }
        if (stderr) {
            process.writeStderr(stderr)
        }
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        process.exit(code)
        docker.handed = process
        process
    }

    // FR17, FR19: a prefix of the file is never returned as the file
    // Scenario: lifecycle/subprocess-supervision / Every reader waits with the one bound
    def "a clean read whose pipe a descendant still writes to at the bound fails instead of returning the prefix"() {
        given:
        def process = heldAfterExit(0, 'partial')

        when:
        channel().readFile('note.txt', 64)

        then:
        def e = thrown(UncheckedIOException)
        e.message.startsWith('in-box read of note.txt output was cut: stdout still open at the post-exit bound of '
                + docker.supervisor().postExitBound())
        !e.message.contains('partial')

        cleanup:
        process.release()
    }

    // FR17: a reader of the exit code alone is refused too — the channel cannot tell a write from a parse
    def "a clean write whose pipe is still written to at the bound fails the write"() {
        given:
        def process = heldAfterExit(0)

        when:
        channel().putFile('note.txt', 'x'.getBytes(StandardCharsets.UTF_8))

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains('in-box write to note.txt output was cut')

        cleanup:
        process.release()
    }

    // FR17: the exit error wins; the cut rides its detail and is not raised beside it
    // Scenario: lifecycle/subprocess-supervision / A failed or killed invocation keeps its partial output
    def "a failed read whose pipe is still written to keeps its own failure with the in-box stderr and the cut named"() {
        given:
        def process = heldAfterExit(1, '', 'head: refused')

        when:
        channel().readFile('note.txt', 64)

        then:
        def e = thrown(UncheckedIOException)
        e.cause.message == 'in-box read of note.txt failed with exit 1; stderr: head: refused(output was cut: stdout still open' +
                ' at the post-exit bound of ' + docker.supervisor().postExitBound() +
                ' — a descendant held its pipes after the command exited)'

        cleanup:
        process.release()
    }

    // FR17: absence is the exit code's answer, which the cut does not touch
    def "an absent file stays absent though its pipe is still written to at the bound"() {
        given:
        def process = heldAfterExit(42)

        expect:
        channel().readFile('note.txt', 64).isEmpty()

        cleanup:
        process.release()
    }

    // FR19: a stdout that broke mid-read is no file either — the prefix it left is not the content
    def "a clean read whose stdout broke mid-read fails instead of returning the prefix"() {
        given: 'a clean exit whose stdout read end fails at the query after the first read, which took the prefix'
        def process = FakeProcess.running()
        process.writeStdout('partial')
        FakePipe pipe = process.stdoutPipe()
        pipe.onQuery { pipe.onQuery { pipe.source().close() } }
        process.exit(0)
        docker.handed = process

        when:
        channel().readFile('note.txt', 64)

        then:
        def e = thrown(UncheckedIOException)
        e.message == 'could not read in-box read of note.txt output'
        e.cause.message == 'Stream Closed'
        pipe.reads() == 1
    }

    // FR11 of bound-subprocess-commands, D17: an interrupt that lands after a clean exit cuts the
    //     joins short, so the read fails by name rather than returning what the drain had
    def "an interrupt landing after a clean exit fails the read by name with the flag kept"() {
        given: 'a clean exit whose content would come back whole, but whose wait returns with the flag set'
        docker.script.stdout = 'whole'.bytes
        docker.script.interruptsAfterWait = true

        when:
        channel().readFile('note.txt', 10)

        then:
        def e = thrown(UncheckedIOException)
        e.cause instanceof InterruptedIOException
        e.message.contains('in-box read of note.txt')

        and:
        Thread.interrupted() // asserts the flag was kept (and clears it)
    }

    // D17: a clean exit with no holder pays nothing — the drains end at end-of-file, the content whole
    def "a clean read with no holder returns the whole content at once"() {
        given:
        def process = FakeProcess.running()
        process.writeStdout('whole')
        process.exit(0)
        docker.handed = process

        when:
        long startedAt = System.nanoTime()
        def read = channel().readFile('note.txt', 64)

        then:
        new String(read.get(), StandardCharsets.UTF_8) == 'whole'
        System.nanoTime() - startedAt <docker.supervisor().postExitBound().toNanos()
    }
}
