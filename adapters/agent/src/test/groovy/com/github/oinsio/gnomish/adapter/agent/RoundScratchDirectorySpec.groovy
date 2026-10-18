package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The round scratch directory (design D18 of fix-terminal-receipt-convergence): one host
 * directory per round that takes the CLI's stderr; the path that ended the round reads its tail —
 * into the failure's detail, or one DEBUG line on success — and the close removes the directory.
 *
 * <p>FR20 of fix-terminal-receipt-convergence.
 */
class RoundScratchDirectorySpec extends Specification {

    @TempDir
    Path parent

    LogCaptureSupport logs

    def cleanup() {
        logs?.detach()
        // A feature that locked a directory unlocks it, so @TempDir can remove it.
        Files.walk(parent).filter { Files.isDirectory(it) }.each {
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString('rwx------'))
        }
    }

    private static Path stderrFile(RoundScratchDirectory scratch) {
        (scratch.stderr() as StderrPolicy.ToFile).file()
    }

    // FR20: a host directory per round, under the parent the equipment names
    def "FR20: open makes a fresh directory under the parent, and the stderr goes to a file in it"() {
        when:
        def first = RoundScratchDirectory.open(parent)
        def second = RoundScratchDirectory.open(parent)

        then:
        def file = stderrFile(first)
        Files.isDirectory(file.parent)
        file.parent.parent == parent
        file.parent != stderrFile(second).parent

        cleanup:
        first?.close()
        second?.close()
    }

    def "FR20: a directory that cannot be made fails the open, naming the parent"() {
        when:
        RoundScratchDirectory.open(parent.resolve('missing'))

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains(parent.resolve('missing').toString())
    }

    // FR20: no file of the round remains on the host after it ended
    def "FR20: close removes the directory and the stderr file in it"() {
        given:
        def scratch = RoundScratchDirectory.open(parent)
        Files.writeString(stderrFile(scratch), 'boom')

        when:
        scratch.close()

        then:
        !Files.exists(stderrFile(scratch).parent)
    }

    def "FR20: the tail is empty when the process wrote no stderr, or never started"() {
        given:
        def scratch = RoundScratchDirectory.open(parent)

        expect:
        scratch.stderrTail() == UntrustedText.agent('')

        cleanup:
        scratch.close()
    }

    def "FR20: a short stderr is read whole, as agent text"() {
        given:
        def scratch = RoundScratchDirectory.open(parent)
        Files.writeString(stderrFile(scratch), 'error: not logged in\n')

        expect:
        scratch.stderrTail() == UntrustedText.agent('error: not logged in\n')

        cleanup:
        scratch.close()
    }

    // FR20: a chatty stderr is bounded by its tail, where the error is
    def "FR20: a stderr larger than the bound is read as its last bytes only"() {
        given:
        def scratch = RoundScratchDirectory.open(parent)
        Files.writeString(stderrFile(scratch), 'HEAD' + ('x' * (200 * 1024)) + 'the error at the end')

        when:
        def tail = scratch.stderrTail()

        then:
        tail.length() == RoundScratchDirectory.STDERR_TAIL_BYTES
        tail.contains('the error at the end')
        !tail.contains('HEAD')

        cleanup:
        scratch.close()
    }

    // FR20, D18 as amended 2026-10-17: a successful round keeps its stderr as one DEBUG line
    def "FR20: a successful round logs the stderr's tail at DEBUG, and only there"() {
        given:
        def scratch = RoundScratchDirectory.open(parent)
        Files.writeString(stderrFile(scratch), 'error: not logged in')
        logs = LogCaptureSupport.attach(RoundScratchDirectory, Level.DEBUG)

        when:
        scratch.logTailOnSuccess()

        then:
        logs.list.size() == 1
        logs.list[0].level == Level.DEBUG
        logs.list[0].formattedMessage.contains('error: not logged in')

        cleanup:
        scratch.close()
    }

    // FR20, D18 as amended 2026-10-17: the tail is read once, by the path that ended the round
    def "FR20: close removes the directory without reading or logging the stderr"() {
        given:
        def scratch = RoundScratchDirectory.open(parent)
        Files.writeString(stderrFile(scratch), 'error: not logged in')
        logs = LogCaptureSupport.attach(RoundScratchDirectory, Level.DEBUG)

        when:
        scratch.close()

        then:
        logs.list.isEmpty()
    }

    def "FR20: a successful round whose CLI wrote no stderr adds no diagnostic line"() {
        given:
        def scratch = RoundScratchDirectory.open(parent)
        logs = LogCaptureSupport.attach(RoundScratchDirectory, Level.DEBUG)

        when:
        scratch.logTailOnSuccess()

        then:
        logs.list.isEmpty()

        cleanup:
        scratch.close()
    }

    // FR20: a failed round carries its tail; with nothing written the failure carries nothing more
    def "FR20: a failure's tail is the stderr's last bytes, as agent text, and empty when nothing was written"() {
        given:
        def scratch = RoundScratchDirectory.open(parent)

        expect:
        scratch.tailForFailure() == UntrustedText.agent('')

        when:
        Files.writeString(stderrFile(scratch), 'HEAD' + ('x' * (200 * 1024)) + 'the error at the end')

        then:
        scratch.tailForFailure().contains('the error at the end')
        !scratch.tailForFailure().contains('HEAD')
        scratch.tailForFailure().provenance() == UntrustedText.agent('').provenance()

        cleanup:
        scratch.close()
    }

    def "FR20: a stderr that cannot be read is a DEBUG line on success, a sentence naming it on failure, and the directory still leaves"() {
        given:
        def scratch = RoundScratchDirectory.open(parent)
        def file = stderrFile(scratch)
        Files.writeString(file, 'secret')
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString('---------'))
        logs = LogCaptureSupport.attach(RoundScratchDirectory, Level.DEBUG)

        when:
        scratch.logTailOnSuccess()
        def failureTail = scratch.tailForFailure()
        scratch.close()

        then:
        logs.list.size() == 1
        logs.list[0].level == Level.DEBUG
        logs.list[0].throwableProxy != null
        failureTail.contains('could not be read')
        !failureTail.contains('secret')
        !Files.exists(file.parent)
    }

    def "FR20: a directory that cannot be removed is one WARN naming it, and the close does not throw"() {
        given:
        def scratch = RoundScratchDirectory.open(parent)
        def file = stderrFile(scratch)
        Files.writeString(file, '')
        Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString('r-x------'))
        logs = LogCaptureSupport.attach(RoundScratchDirectory, Level.INFO)

        when:
        scratch.close()

        then:
        notThrown(Exception)
        logs.list.size() == 1
        logs.list[0].level == Level.WARN
        logs.list[0].formattedMessage.startsWith(OperatorEvent.ROUND_SCRATCH_REMOVAL_FAILED.head())
        logs.list[0].formattedMessage.contains(file.parent.toString())
        logs.list[0].throwableProxy != null
    }
}
