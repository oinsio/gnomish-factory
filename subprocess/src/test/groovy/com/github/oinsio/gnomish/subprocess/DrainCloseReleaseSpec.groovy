package com.github.oinsio.gnomish.subprocess

import com.sun.management.HotSpotDiagnosticMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16, FR17, design D15 of fix-terminal-receipt-convergence, on real threads (`lock-scope.md`): the
 * JDK facts the post-exit bound rests on, pinned on a real process whose stdout a descendant holds.
 *
 * <p>A drain parked in {@code read()} holds the pipe stream's monitor; the JDK's reaper, on the
 * child's exit, calls the {@code synchronized} {@code processExited} and waits for that monitor for
 * as long as the descendant keeps the pipe (JDK-8169565). On JDK 25 {@code close()} takes the
 * stream's own close lock instead, closes the descriptor and wakes the parked read (measured: about
 * 2 ms), and the reaper then gets the monitor. {@link Drain#joinAll} is that close.
 *
 * <p>The order "the drain is parked, then the child exits" is made, not assumed: the drain is seen
 * in {@code FileInputStream.readBytes} in a thread dump before the child is cued to exit by the end
 * of its stdin. Had the reaper come first, it would have closed the pipe itself and nothing here
 * would be exercised. The reaper is told apart from those of earlier specs by its thread id.
 */
class DrainCloseReleaseSpec extends Specification implements FakeBinaries {

    private static final String DRAIN = 'drain-close-release-stdout'

    @TempDir
    Path dir

    // Scenario: lifecycle/subprocess-supervision / Closing the pipe releases the parked drain
    def "FR16, FR17: closing a held pipe releases the parked drain within the bound, and the reaper with it"() {
        given: 'a child whose stdout a descendant holds, and the post-exit bound of a short kill grace'
        Path pidFile = dir.resolve('holder.pid')
        Duration bound = new ProcessSupervisor(Duration.ofMillis(300)).postExitBound()
        Process process = new ProcessBuilder(fakeBinary('exit-on-cue').toString(), pidFile.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

        and: 'a drain parked reading the pipe before the child exits'
        Drain drain = Drain.start(process.inputStream, DRAIN)
        eventually('the child spoke, its holder up and named') {
            drain.captured().contains('spoken')
        }
        eventually('the drain is parked in a read') {
            stackOf(DRAIN).any {
                it.contains('FileInputStream.readBytes')
            }
        }
        Set<String> earlierReapers = threadsIn('processExited')

        when: 'the child is cued and exits cleanly, the descendant still holding the pipe'
        process.outputStream.close()
        boolean exited = process.waitFor(bound.toMillis(), TimeUnit.MILLISECONDS)

        then: 'the reaper waits on the monitor the parked read holds (JDK-8169565)'
        exited
        process.exitValue() == 0
        eventually('the reaper is held in processExited') {
            !(threadsIn('processExited') - earlierReapers).isEmpty()
        }
        holder(pidFile).isAlive()

        when: 'the drain is joined with the bound, and closed when the bound expires'
        Set<String> heldReapers = threadsIn('processExited') - earlierReapers
        boolean cut = within(bound.multipliedBy(2), 'drain-close-release-join') {
            Drain.joinAll(bound, drain)
        }
        long closedAt = System.nanoTime()
        String captured = drain.join(bound)
        Duration release = Duration.ofNanos(System.nanoTime() - closedAt)

        then: 'the parked read ended at the close, within milliseconds, keeping what it had read'
        cut
        release <Duration.ofMillis(500)
        captured.contains('spoken')
        stackOf(DRAIN).isEmpty()

        and: 'the reaper is released: it leaves processExited, and the exit future completes'
        eventually('the reaper leaves processExited') {
            (threadsIn('processExited').intersect(heldReapers)).isEmpty()
        }
        process.onExit().get(bound.toMillis(), TimeUnit.MILLISECONDS).is(process)

        when: 'the stream is read a second time'
        within(bound, 'drain-close-release-second-read') {
            process.inputStream.read()
        }

        then: 'the read takes the monitor at once, and finds the stream closed'
        thrown(IOException)

        and: 'the descendant was never waited on: it still holds its end'
        holder(pidFile).isAlive()

        cleanup:
        process?.destroyForcibly()
        if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
                killQuietly(it)
            }
        }
    }

    /**
     * Runs {@code work} on a virtual thread of its own and waits for it at most {@code bound}, so a
     * call that blocks fails the spec with a timeout instead of hanging it; what {@code work} throws
     * is rethrown as it is.
     */
    private static <T> T within(Duration bound, String name, Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(work)
        Thread.ofVirtual().name(name).start(task)
        try {
            return task.get(bound.toMillis(), TimeUnit.MILLISECONDS)
        } catch (ExecutionException failed) {
            throw failed.cause
        }
    }

    private static ProcessHandle holder(Path pidFile) {
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()
    }

    /** The frames of the live thread named {@code name}, virtual threads included; empty when none lives. */
    private List<String> stackOf(String name) {
        threadDump().findAll {
            it.name == name
        }.collectMany {
            it.stack as List<String>
        }
    }

    /** The ids of every live thread with a frame of {@code method} on its stack. */
    private Set<String> threadsIn(String method) {
        threadDump().findAll { thread ->
            thread.stack.any {
                it.contains(".${method}(")
            }
        }
        .collect { it.tid as String } as Set
    }

    /**
     * A plain-text thread dump of this JVM, one entry per thread: {@code tid}, {@code name} and the
     * {@code stack} lines. {@code Thread.getAllStackTraces} leaves virtual threads out, and the drain
     * is one; the dump lists both kinds.
     */
    private List<Map> threadDump() {
        Path file = Files.createTempFile(dir, 'threads', '.txt')
        Files.delete(file)
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean)
                .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN)
        Files.readString(file).split(/\n\s*\n/).collect {
            it.readLines()
        }.findResults { List<String> lines ->
            def header = lines ? lines.first() =~ /^#(\d+) "([^"]*)"/ : null
            header?.find() ? [tid: header.group(1), name: header.group(2), stack: lines.drop(1)*.trim()] : null
        }
    }
}
