package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * FR1, NFR-S3 of add-sandbox-core: the container file channel's exec-pipe
 * mechanics verified without a daemon (a scripted fake {@link DockerCli} and
 * {@link Process}) — the read cap truncates over-cap bytes exactly and stays
 * silent at exactly-the-cap, putFile waits for its stdin pump before judging the
 * exit, and an interrupted wait preserves the interrupt flag while surfacing the
 * write failure. Real in-box streaming is covered by the Docker-gated contract
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

    private static List<ILoggingEvent> capture(Closure emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(ContainerFileChannel)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
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

    // FR1, NFR-R1: an interrupted wait surfaces the write failure (exit -1) and the interrupt
    // flag survives both the exec wait and the pump join — never swallowed
    def "an interrupted putFile preserves the interrupt flag and reports the failed wait"() {
        when:
        Thread.currentThread().interrupt()
        channel().putFile('note.txt', 'x'.getBytes(StandardCharsets.UTF_8))

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains('exit -1')
        Thread.interrupted() // asserts the flag was restored (and clears it)
    }
}
