package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.sun.management.HotSpotDiagnosticMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R1 of fix-round-stdout-drain; D17 of fix-terminal-receipt-convergence, on real threads
 * (`lock-scope.md`): the round's stdout drain, parked on a real pipe a descendant holds after the
 * agent process exited, is released by {@link StreamDrain#close()} itself — the close adds no join
 * of its own, so the release is the stream's close, and no drain thread is left parked.
 *
 * <p>The order "the drain is parked, then the child exits" is made, not assumed: the drain is seen
 * in {@code FileInputStream.readBytes} in a thread dump before the child is cued by the end of its
 * stdin (the shape of {@code DrainCloseReleaseSpec} in {@code :subprocess}).
 */
class StreamDrainCloseReleaseSpec extends Specification {

    private static final String DRAIN = 'agent-stdout-drain'

    @TempDir
    Path dir

    def "close releases a drain parked on a pipe a descendant still holds, and leaves no drain thread"() {
        given: 'an agent process whose stdout a descendant holds, and a drain parked reading it'
        Path pidFile = dir.resolve('holder.pid')
        Process process = new ProcessBuilder(StandIn.process('exit-on-cue').toString(), pidFile.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        StreamDrain drain = StreamDrain.start(process.inputStream, new VirtualClock(), { event -> })
        eventually('the child spoke') { drain.bytesRead() > 0 }
        eventually('the drain is parked in a read') {
            drainStack().any { it.contains('FileInputStream.readBytes') }
        }

        when: 'the child exits cleanly, the descendant still holding the pipe'
        process.outputStream.close()
        boolean exited = process.waitFor(5, TimeUnit.SECONDS)

        and: 'the round tears the drain down'
        long startedAt = System.nanoTime()
        drain.close()
        Duration closing = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the close returned at once, and the parked drain ended with it'
        exited
        closing <Duration.ofMillis(500)
        eventually('no drain thread is parked on the pipe') {
            !drainStack().any { it.contains('FileInputStream.readBytes') }
        }
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow().isAlive()

        cleanup:
        process?.destroyForcibly()
        if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
                it.destroyForcibly()
            }
        }
    }

    /** Polls {@code condition} for up to two seconds — far inside the drain's own ten-minute holder. */
    private static void eventually(String what, Closure<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() <deadline) {
            if (condition.call()) {
                return
            }
            Thread.sleep(10)
        }
        throw new AssertionError("timed out waiting until ${what}" as Object)
    }

    /**
     * The frames of every live drain thread, virtual threads included. A drain of an earlier spec may
     * still be unwinding elsewhere; only one parked in a file read is this spec's subject.
     */
    private List<String> drainStack() {
        Path file = Files.createTempFile(dir, 'threads', '.txt')
        Files.delete(file)
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean)
                .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN)
        Files.readString(file).split(/\n\s*\n/).collect {
            it.readLines()
        }.findAll { List<String> lines ->
            lines && lines.first() =~ /^#\d+ "${DRAIN}"/
        }.collectMany { it.drop(1)*.trim() }
    }
}
