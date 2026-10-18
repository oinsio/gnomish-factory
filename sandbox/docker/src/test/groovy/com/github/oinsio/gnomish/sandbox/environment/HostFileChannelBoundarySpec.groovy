package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.InstantSource
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-S3, FR17 of add-sandbox-core: the host file channel's boundary mechanics —
 * a relative factory-chosen path anchors on the working copy (with missing
 * parent directories created), a non-positive read cap is refused outright, and
 * an over-cap read warns about the truncation it performs.
 */
class HostFileChannelBoundarySpec extends Specification {

    @TempDir
    Path workingCopy

    @TempDir
    Path outsideRoot

    private final InstantSource clock = new VirtualClock()

    HostTaskExecutionEnvironment env

    def setup() {
        env = new HostTaskExecutionEnvironment(workingCopy, clock, TestChildEnvironment.hostAllowlist(), ProcessLaunch.grouped())
        env.materialize('task/channel', null)
    }

    def cleanup() {
        env.dispose()
    }

    private static List<ILoggingEvent> capture(Closure<?> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(HostTaskExecutionEnvironment)
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

    // NFR-S3, FR17: a relative path anchors on the working copy — never the factory cwd — and
    // putFile creates the missing parent directories on the way
    def "a relative putFile lands under the working copy, creating missing parents"() {
        given:
        def bytes = 'payload'.getBytes(StandardCharsets.UTF_8)

        when:
        env.putFile('nested/deep/file.txt', bytes)

        then: 'the file exists at the working-copy-anchored path and round-trips'
        Files.readAllBytes(workingCopy.resolve('nested/deep/file.txt')) == bytes
        env.readFile('nested/deep/file.txt', 1024).get() == bytes
    }

    // NFR-S3, FR17: a symlink inside a root that resolves outside both roots refuses the write —
    //     through an existing link to an outside file, and through a linked parent directory
    // Scenario: sandbox/execution-environment / Symlink escape on a host write is refused
    def "putFile through a symlink resolving outside both roots is refused and nothing outside changes"() {
        given: 'an outside file and an outside directory, each reachable through a symlink in the working copy'
        def outside = outsideRoot
        def victim = outside.resolve('victim.txt')
        Files.writeString(victim, 'original')
        Files.createSymbolicLink(workingCopy.resolve('to-file'), victim)
        Files.createSymbolicLink(workingCopy.resolve('to-dir'), outside)

        when: 'the factory writes through the file link'
        env.putFile('to-file', 'planted'.getBytes(StandardCharsets.UTF_8))

        then: 'refused, and the outside file is unmodified'
        thrown(PathEscapeException)
        Files.readString(victim) == 'original'

        when: 'the factory writes a new file under the directory link'
        env.putFile('to-dir/created.txt', 'planted'.getBytes(StandardCharsets.UTF_8))

        then: 'refused, and no file was created outside the roots'
        thrown(PathEscapeException)
        !Files.exists(outside.resolve('created.txt'))
    }

    // NFR-S3: a zero cap is a caller bug, refused before any filesystem access
    def "readFile refuses a zero size cap"() {
        when:
        env.readFile('anything', 0)

        then:
        thrown(IllegalArgumentException)
    }

    // NFR-S3, NFR-O1: an over-cap read truncates AND says so — the warn names the cap breach
    def "an over-cap read returns exactly the cap and logs the truncation warning"() {
        given: 'a channel file twice the cap'
        env.putFile('big.txt', ('x' * 20).getBytes(StandardCharsets.UTF_8))
        def read = null

        when:
        def events = capture { read = env.readFile('big.txt', 10) }

        then:
        read.get().length == 10
        events.any {
            it.level == Level.WARN && it.formattedMessage.contains('exceeded read cap')
        }
    }
}
