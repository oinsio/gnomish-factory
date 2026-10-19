package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9, M3, design D7 (as amended 2026-10-18) of make-pipe-reads-non-parking, in the shape of Go's
 * {@code Exit-hang} test: a real child exits while a descendant that left its process group for a
 * session of its own keeps writing to the inherited stdout. No factory thread holds the stream's
 * monitor, so the JDK's reaper drains and closes the pipe at the exit, the drain ends a tick later,
 * and the capture is complete and not flagged. No order is stated: the holder writes continuously,
 * so whether the drain read before or after the exit decides nothing asserted here.
 */
class EscapedHolderSpec extends Specification implements FakeBinaries {

    @TempDir
    Path dir

    // Scenario: lifecycle/subprocess-supervision / An escaped holder pins no thread
    def "FR9, M3: a child whose escaped descendant keeps writing returns at once, complete and unflagged, pinning no thread"() {
        given: 'a binary that speaks, leaves a writing holder in a session of its own, and exits 0'
        Path pidFile = dir.resolve('holder.pid')
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('escaped-holder').toString(), pidFile.toString())
        TestChildEnvironment.cleared(builder)

        and: 'the real grouped launch, recording the process it started'
        List<Process> started = []
        ProcessLaunch grouped = ProcessLaunch.grouped()
        ProcessLaunch recording = { ProcessBuilder command, StdinSource stdin ->
            Process process = grouped.start(command, stdin)
            started << process
            process
        } as ProcessLaunch

        and: 'a kill grace long enough that a return paying the post-exit bound cannot pass for one at once'
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofSeconds(10))
        Duration bound = supervisor.postExitBound()

        and: 'the threads already in a pipe read before the run, which are not the run\'s'
        Set<Thread> before = inPipeRead()

        when:
        long startedAt = System.nanoTime()
        Captured captured = new CaptureRunner(supervisor, recording).run(builder, Duration.ofSeconds(30))
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the exit, with every byte the child wrote before it, and the capture not flagged'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 0
        List<String> lines = captured.stdout().readLines()
        lines.first() == 'spoken'
        lines.tail().every { it == 'held' }
        !captured.truncated()

        and: 'the return came well inside the post-exit bound'
        elapsed <bound.dividedBy(5)

        and: 'no thread the run started is left on the pipe'
        (inPipeRead() - before).isEmpty()

        and: 'the exit handling of the process completed'
        started.size() == 1
        started.first().onExit().get(5, TimeUnit.SECONDS) != null

        and: 'the descendant is still running'
        holder(pidFile).isAlive()

        cleanup:
        if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
                killQuietly(it)
            }
        }
    }

    /** The live threads with a {@code FileInputStream} read on their stack — a read of a pipe. */
    private static Set<Thread> inPipeRead() {
        Thread.allStackTraces.findAll { Thread thread, StackTraceElement[] stack ->
            thread.alive && stack.any {
                it.className == 'java.io.FileInputStream' && it.methodName.startsWith('read')
            }
        }.keySet()
    }

    private static ProcessHandle holder(Path pidFile) {
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()
    }
}
