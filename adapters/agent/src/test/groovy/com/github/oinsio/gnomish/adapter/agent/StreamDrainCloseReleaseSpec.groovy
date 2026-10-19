package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.subprocess.ChildOutput
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
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
 * NFR-R1 of fix-round-stdout-drain; D17 of fix-terminal-receipt-convergence; FR6, M3, D5 of
 * make-pipe-reads-non-parking, on a real process and real threads: the round's close of its stdout
 * drain, while the agent process lives and a descendant holds its stdout, is the owner's stop — it
 * returns at once, closes no pipe, and the drain thread ends within a tick, on Linux as on macOS,
 * where closing a pipe never wakes a read parked in it.
 *
 * <p>No order is stated: whether the drain was between two queries or inside its tick when the
 * close came decides nothing asserted here, since the drain never reads bytes the pipe has not
 * reported. The bound on the drain's end is far past a tick, and the drain thread is identified by
 * a thread dump taken before and after, which lists virtual threads.
 */
class StreamDrainCloseReleaseSpec extends Specification {

    private static final String DRAIN = 'agent-stdout-drain'

    @TempDir
    Path dir

    // Scenario: executor/agent-executor / The round's close leaves no drain thread
    def "M3: the round's close returns at once while a descendant holds the pipe, and leaves no drain thread"() {
        given: 'an agent process that spoke and lives on, its stdout held by a descendant too'
        Path pidFile = dir.resolve('holder.pid')
        ProcessBuilder builder = new ProcessBuilder(StandIn.process('exit-on-cue').toString(), pidFile.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
        TestChildEnvironment.cleared(builder)
        Set<String> before = drainThreads()
        Process process = builder.start()

        and: 'the round draining it'
        StreamDrain drain = StreamDrain.start(ChildOutput.stdout(process), new VirtualClock(), { event -> })
        eventually('the child spoke') {
            drain.bytesRead() > 0 && Files.exists(pidFile)
        }
        Set<String> running = drainThreads() - before

        when: 'the round ends and closes its drain'
        long startedAt = System.nanoTime()
        drain.close()
        Duration closing = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the dump saw this round\'s drain thread while it ran'
        running.size() == 1

        and: 'the close returned at once'
        closing <Duration.ofMillis(500)

        and: 'the drain ended within a bound far past a tick, and no drain thread of the round is left'
        drain.await(Duration.ofSeconds(1)) != null
        (drainThreads() - before).isEmpty()

        and: 'the agent process and the descendant holding its pipe are both still alive'
        process.alive
        holder(pidFile).isAlive()

        cleanup:
        process?.destroyForcibly()
        if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
                it.destroyForcibly()
            }
        }
    }

    /** Polls {@code condition} for up to five seconds — far inside the descendant's ten minutes. */
    private static void eventually(String what, Closure<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() <deadline) {
            if (condition.call()) {
                return
            }
            Thread.sleep(10)
        }
        throw new AssertionError("timed out waiting until ${what}" as Object)
    }

    private static ProcessHandle holder(Path pidFile) {
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()
    }

    /** The thread ids of every live drain thread, virtual threads included, from a plain-text dump. */
    private Set<String> drainThreads() {
        Path file = Files.createTempFile(dir, 'threads', '.txt')
        Files.delete(file)
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean)
                .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN)
        Files.readAllLines(file).findResults { String line ->
            def header = line =~ /^#(\d+) "${DRAIN}"/
            header.find() ? header.group(1) : null
        } as Set<String>
    }
}
