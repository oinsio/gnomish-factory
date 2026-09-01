package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR1, NFR-S3 of add-sandbox-core: the container file channel's exec-pipe
 * mechanics verified without a daemon (a scripted fake {@link DockerCli} and
 * {@link Process}) — the read cap truncates over-cap bytes exactly and stays
 * silent at exactly-the-cap, putFile waits for its stdin pump before judging the
 * exit, and an interrupted wait preserves the interrupt flag while naming the
 * interruption rather than coding it as an exit value. Real in-box streaming is covered by the Docker-gated contract
 * spec.
 */
class ContainerFileChannelSpec extends Specification {

    ScriptedFileChannelDockerCli docker = new ScriptedFileChannelDockerCli()

    def cleanup() {
        Thread.interrupted() // never leak an interrupt flag into the next feature
    }

    private ContainerFileChannel channel() {
        new ContainerFileChannel(docker, 'k1', '/gnomish/work', '/gnomish/scratch')
    }

    /** Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.3 touched this spec. */
    private static List<ILoggingEvent> capture(Closure emit) {
        def logs = LogCaptureSupport.attach(ContainerFileChannel)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    // NFR-S3: an over-cap read is truncated to exactly the first cap bytes — the copy is real
    def "an over-cap read returns exactly the first cap bytes"() {
        given: 'the in-box file yields one byte more than the cap'
        docker.process.stdout = 'abcdefghijK'.getBytes(StandardCharsets.UTF_8)

        when:
        def read = channel().readFile('note.txt', 10)

        then:
        new String(read.get(), StandardCharsets.UTF_8) == 'abcdefghij'
    }

    // NFR-S3: a file of exactly the cap is whole, untruncated, and logs no truncation warning
    def "a read of exactly the cap comes back whole with no truncation warning"() {
        given:
        def bytes = ('x' * 10).getBytes(StandardCharsets.UTF_8)
        docker.process.stdout = bytes
        def read = null

        when:
        def events = capture { read = channel().readFile('note.txt', 10) }

        then:
        read.get() == bytes
        events.findAll { it.level == Level.WARN }.isEmpty()
    }

    // FR5, D4 of harden-logging-observability: a truncation is a degraded read, and an operator
    //     running several boxes needs to know which one it happened in
    def "FR5: the truncation warning names the environment the over-cap file was read from"() {
        given:
        docker.process.stdout = 'abcdefghijK'.getBytes(StandardCharsets.UTF_8)

        when:
        def events = capture { channel().readFile('note.txt', 10) }

        then:
        def warning = events.find { it.level == Level.WARN }
        warning.formattedMessage.contains('note.txt')
        warning.formattedMessage.contains('k1')
    }

    // FR1, D18: putFile returns only after the stdin pump delivered and closed the exec pipe
    def "putFile waits for the stdin pump to finish delivering the content"() {
        given: 'a stdin sink that takes a while to close'
        docker.process.stdin.closeDelayMillis = 250
        def bytes = 'payload'.getBytes(StandardCharsets.UTF_8)

        when:
        channel().putFile('note.txt', bytes)

        then: 'the pump had finished before putFile returned'
        docker.process.stdin.closed
        docker.process.stdin.toByteArray() == bytes
    }

    // FR1, NFR-R1: an interrupt pending when the pump join begins makes join() throw — the catch
    // must restore the flag for callers up the stack while the successful write still returns
    def "an interrupt hitting the pump join is preserved while the write itself still succeeds"() {
        given: 'a pump still alive at join time (slow stdin close) and a wait that leaves the flag set'
        docker.process.stdin.closeDelayMillis = 2000
        docker.process.interruptOnWaitFor = true

        when:
        channel().putFile('note.txt', 'x'.getBytes(StandardCharsets.UTF_8))

        then: 'the write succeeded (exit 0) and the interrupt survived the interrupted join'
        noExceptionThrown()
        Thread.interrupted() // asserts the flag was restored (and clears it)
    }

    // FR11 of bound-subprocess-commands: an interrupted wait is reported by name — an
    // InterruptedIOException, not the exit -1 an in-box script could itself have chosen — and the
    // interrupt flag survives both the exec wait and the pump join
    def "an interrupted putFile names the interruption instead of coding it as exit -1"() {
        when:
        Thread.currentThread().interrupt()
        channel().putFile('note.txt', 'x'.getBytes(StandardCharsets.UTF_8))

        then:
        def e = thrown(UncheckedIOException)
        e.cause instanceof InterruptedIOException
        e.message.contains('in-box write to note.txt')
        !e.message.contains('exit -1')

        and:
        Thread.interrupted() // asserts the flag was restored (and clears it)
    }

    // FR11: the same naming on the read path — an interrupted read is never an in-box exit code
    def "an interrupted readFile names the interruption instead of coding it as exit -1"() {
        when:
        Thread.currentThread().interrupt()
        channel().readFile('note.txt', 10)

        then:
        def e = thrown(UncheckedIOException)
        e.cause instanceof InterruptedIOException
        e.message.contains('in-box read of note.txt')

        and:
        Thread.interrupted()
    }
}
