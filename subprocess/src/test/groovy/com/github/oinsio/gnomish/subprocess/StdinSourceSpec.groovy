package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR23, design D20 of fix-terminal-receipt-convergence: a child's standard input is a file the
 * launch owner writes, hands the child and deletes, or {@code /dev/null} — never a pipe the factory
 * writes into. The children are real processes on purpose: what is measured is what the OS does
 * with an input descriptor a descendant inherited, and none of that survives being faked.
 *
 * <p>Each feature hands the owner an input directory of its own ({@code grouped(Path)}), so "the
 * file is gone" is a listing of that directory and no JVM-wide property is touched.
 */
class StdinSourceSpec extends Specification implements FakeBinaries {

    /** More than any pipe buffer holds (16–64 KiB): a pipe writer would block on it. */
    private static final int ONE_MIB = 1024 * 1024

    @TempDir
    Path inputs

    @TempDir
    Path work

    // Scenario: lifecycle/subprocess-supervision / A held stdin stalls no thread of the factory
    def "FR23: a child that exits while a descendant holds its stdin returns at the exit, its input file gone"() {
        given: 'a child that exits at once and leaves a holder of its stdin alone, the output sent away'
        Path pidFile = work.resolve('holder.pid')
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('stdin-holder').toString(), pidFile.toString())
        TestChildEnvironment.cleared(builder)
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofMillis(300))
        Set<Thread> before = Thread.getAllStackTraces().keySet()

        and: 'the real group sweep, noting whether the holder was alive when it began'
        Map<String, Boolean> atSweep = [:]
        ProcessGroupSweep sweep = new ProcessGroupSweep(new ProcessProbes(GroupedLaunch.groupSignal(), [], {
            atSweep.holderAlive = holder(pidFile).isAlive()
        }))

        when: 'it is run with more input than a pipe could take'
        long startedAt = System.nanoTime()
        Captured captured = new CaptureRunner(supervisor, ProcessLaunch.grouped(inputs), sweep)
                .run(builder, Duration.ofSeconds(30), new byte[ONE_MIB])
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the clean exit is reported, complete, and the return came at the exit, not on a bound'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 0
        !captured.truncated()
        elapsed <supervisor.postExitBound()

        and: 'the input file is gone, and the holder of the child\'s stdin, alive when the drains let go,'
        and: 'was ended by the group sweep (D19)'
        Files.list(inputs).withCloseable { it.toList() } == []
        atSweep.holderAlive
        ended(pidFile)

        and: 'no thread works for the child any longer: the drains were joined (the capture is not cut),'
        and: 'the calling thread is back, and no reaper still waits on an exit'
        eventually('no thread works for the child') {
            busyThreadsSince(before).isEmpty()
        }

        cleanup:
        killHolder(pidFile)
    }

    // Scenario: lifecycle/subprocess-supervision / A child with no input reads end-of-file at once
    def "FR23: a child given no input reads end-of-file at once, and no file is written for it"() {
        given: 'wc, which counts its stdin and exits only at its end'
        ProcessBuilder builder = new ProcessBuilder('wc', '-c')
        TestChildEnvironment.cleared(builder)

        when:
        Process process = ProcessLaunch.grouped(inputs).start(builder, StdinSource.NONE)

        then: 'it counts nothing and exits on its own'
        process.waitFor(10, TimeUnit.SECONDS)
        process.inputStream.text.trim() == '0'

        and: 'its stdin is /dev/null, and no file was written for it'
        builder.redirectInput().file() == new File('/dev/null')
        Files.list(inputs).withCloseable { it.toList() } == []

        when: 'the factory side of the child\'s stdin is written to'
        process.outputStream.write(0)

        then: 'there is no pipe behind it: the JDK hands out its closed null stream'
        thrown(IOException)

        cleanup:
        process?.destroyForcibly()
    }

    // Scenario: lifecycle/subprocess-supervision / Input reaches the child from a file the owner deletes
    def "FR23: a child reads the whole of its input after the owner deleted the file"() {
        given: 'wc, and an input of one MiB'
        ProcessBuilder builder = new ProcessBuilder('wc', '-c')
        TestChildEnvironment.cleared(builder)

        when:
        Process process = ProcessLaunch.grouped(inputs).start(builder, StdinSource.of(new byte[ONE_MIB]))

        then: 'the file is gone as soon as the launch returned'
        Files.list(inputs).withCloseable { it.toList() } == []

        and: 'the child still read every byte through the descriptor it inherited'
        process.waitFor(10, TimeUnit.SECONDS)
        process.inputStream.text.trim() == String.valueOf(ONE_MIB)

        cleanup:
        process?.destroyForcibly()
    }

    def "FR23: the source holds its own copy of the input"() {
        given: 'cat, which echoes its input'
        byte[] input = 'whole'.getBytes(StandardCharsets.UTF_8)
        StdinSource source = StdinSource.of(input)
        ProcessBuilder builder = new ProcessBuilder('cat')
        TestChildEnvironment.cleared(builder)

        when: 'the caller changes its array after building the source'
        input[0] = (byte) 'h'
        Process process = ProcessLaunch.grouped(inputs).start(builder, source)

        then: 'the child reads what the source was built from'
        process.waitFor(10, TimeUnit.SECONDS)
        process.inputStream.text == 'whole'

        cleanup:
        process?.destroyForcibly()
    }

    // Scenario: lifecycle/subprocess-supervision / Input reaches the child from a file the owner deletes
    def "FR23: an input directory the owner cannot write to fails the launch before any process starts"() {
        given: 'a read-only input directory, and a command whose run would leave a mark'
        Files.setPosixFilePermissions(inputs, PosixFilePermissions.fromString('r-x------'))
        Path mark = work.resolve('started')
        ProcessBuilder builder = new ProcessBuilder('touch', mark.toString())
        TestChildEnvironment.cleared(builder)

        when:
        ProcessLaunch.grouped(inputs).start(builder, StdinSource.of(new byte[1]))

        then: 'the launch exception names the directory, and the command never ran'
        AccessDeniedException refused = thrown()
        refused.file.startsWith(inputs.toString())
        !Files.exists(mark)

        cleanup:
        Files.setPosixFilePermissions(inputs, PosixFilePermissions.fromString('rwx------'))
    }

    def "FR23: a launch that fails after the file was written leaves no file behind"() {
        when:
        ProcessLaunch.grouped(inputs).start(new ProcessBuilder('/nonexistent/gnomish-no-such-binary'), StdinSource.of(new byte[1]))

        then:
        thrown(IOException)
        Files.list(inputs).withCloseable { it.toList() } == []
    }

    /**
     * The threads started since {@code before} that still work for a child: one in this module's
     * code (a drain or a writer), or a JDK reaper still waiting on a process's exit. The drains are
     * virtual threads, which a stack-trace listing does not show, so their end is proven by the
     * capture not being cut; an idle reaper or carrier parks in its pool and is not counted.
     */
    private static List<String> busyThreadsSince(Set<Thread> before) {
        Thread.getAllStackTraces().findAll { Thread thread, StackTraceElement[] stack ->
            !(thread in before) && thread.alive && stack.any {
                it.className.startsWith('com.github.oinsio.gnomish.subprocess.') || it.methodName == 'waitForProcessExit0'
            }
        }.collect { thread, stack -> thread.name }
    }

    private static ProcessHandle holder(Path pidFile) {
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()
    }

    /** The holder outlives its parent by design until the group sweep; this ends it if a spec failed first. */
    private void killHolder(Path pidFile) {
        if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
                killQuietly(it)
            }
        }
    }
}
