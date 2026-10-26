package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR1, NFR-S3 of add-sandbox-core: the container file channel's exec-pipe
 * mechanics verified without a daemon (a scripted fake {@link DockerCli} and
 * {@link Process}) — the read cap truncates over-cap bytes exactly and stays
 * silent at exactly-the-cap, and an interrupted wait preserves the interrupt flag
 * while naming the interruption rather than coding it as an exit value. The
 * post-exit joins and the cut rule live in {@code ContainerFileChannelPostExitSpec}. Real in-box streaming is covered by the Docker-gated contract
 * spec.
 */
class ContainerFileChannelSpec extends Specification {

    ScriptedFileChannelDockerCli docker = new ScriptedFileChannelDockerCli()

    def cleanup() {
        Thread.interrupted() // never leak an interrupt flag into the next feature
    }

    private ContainerFileChannel channel() {
        new ContainerFileChannel(docker, new ContainerExecLaunches('k1', '/gnomish/work', [] as Set), '/gnomish/work', '/gnomish/scratch')
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
        docker.script.stdout = 'abcdefghijK'.getBytes(StandardCharsets.UTF_8)

        when:
        def read = channel().readFile('note.txt', 10)

        then:
        new String(read.get(), StandardCharsets.UTF_8) == 'abcdefghij'
    }

    // NFR-S3: a file of exactly the cap is whole, untruncated, and logs no truncation warning
    def "a read of exactly the cap comes back whole with no truncation warning"() {
        given:
        def bytes = ('x' * 10).getBytes(StandardCharsets.UTF_8)
        docker.script.stdout = bytes
        def read = null

        when:
        def events = capture { read = channel().readFile('note.txt', 10) }

        then:
        read.get() == bytes
        events.findAll { it.level == Level.WARN }.isEmpty()
    }

    // FR5, D4 of harden-logging-observability: a truncation is a degraded read, and an operator
    //     running several boxes needs to know which one it happened in
    // Scenario: sandbox/execution-environment / Oversized protocol file cannot exhaust the factory
    def "FR5: the truncation warning names the environment the over-cap file was read from"() {
        given:
        docker.script.stdout = 'abcdefghijK'.getBytes(StandardCharsets.UTF_8)

        when:
        def events = capture { channel().readFile('note.txt', 10) }

        then:
        def warning = events.find { it.level == Level.WARN }
        warning.formattedMessage.startsWith(OperatorEvent.CONTAINER_CHANNEL_FILE_TRUNCATED.head())
        warning.formattedMessage.contains('note.txt')
        warning.formattedMessage.contains('k1')
    }

    // FR11 of bound-subprocess-commands: an interrupted wait is reported by name — an
    // InterruptedIOException, not the exit -1 an in-box script could itself have chosen — and the
    // interrupt flag survives the exec wait and the drain joins
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
    // Scenario: sandbox/execution-environment / Interruption is not an exit code
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

    // FR17: a path that normalizes outside both the working copy and the scratch root is refused
    // before any docker exec is started — never sent in-box for the exec script to reject.
    // Scenario: sandbox/execution-environment / File-channel paths resolve under the two roots
    def "a path escaping both the working copy and the scratch root is refused before any docker exec"() {
        when:
        channel().readFile('../../etc/passwd', 10)

        then:
        thrown(PathEscapeException)
        docker.starts.isEmpty()
    }

    // FR17: .git/** under the working copy is refused alongside root escapes (validate's second
    // branch), so no channel write can plant a hook or rewrite repository internals in the in-box
    // clone — kept in sync with the host adapter's ChannelPathResolver per this class's javadoc.
    def "a path under the working copy's .git directory is refused before any docker exec"() {
        when:
        channel().putFile('.git/hooks/pre-commit', 'x'.getBytes(StandardCharsets.UTF_8))

        then:
        thrown(PathEscapeException)
        docker.starts.isEmpty()
    }
}
