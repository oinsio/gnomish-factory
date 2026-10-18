package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, NFR-O3, design D19 of fix-terminal-receipt-convergence: every waiter of this module sweeps
 * the process group of the host process it waited for — {@link HostExecHandle} after each wait, the
 * {@code docker} client of a box command included; {@link ContainerFileChannel} after its drains
 * ({@code DockerCliGroupSweepSpec} has the management commands) — and a sweep that found a survivor
 * says one DEBUG line. The survivors are real: a silent orphan a real child left behind, or a {@link
 * GroupLeader} handed to the code under test as a {@link FakeProcess}'s pid.
 */
class HostSweepSpec extends Specification {

    @TempDir
    Path work

    private final VirtualClock clock = new VirtualClock()

    // Scenario: lifecycle/subprocess-supervision / A reparented orphan dies with the invocation
    def "FR21, NFR-O3: #wait sweeps the group, ending the silent orphan the child left, with one DEBUG line"() {
        given: 'a child that exits at once and leaves a holder of its stdin alone, its output sent away'
        Path pidFile = work.resolve('holder.pid')
        Process process = grouped(StandIn.process('stdin-holder').toString(), pidFile.toString())
        HostExecHandle handle = new HostExecHandle(process, clock.instant())

        when:
        List<ILoggingEvent> lines = LogCaptureSupport.capture(HostSweep, Level.DEBUG) {
            waiting.call(handle, clock)
        }

        then: 'the holder is gone'
        ended(pidFile)

        and: 'one DEBUG line names the invocation and what the sweep found'
        lines.size() == 1
        lines[0].level == Level.DEBUG
        lines[0].formattedMessage.startsWith("host process ${process.pid()}: process group swept after the exit: rounds=")

        cleanup:
        killQuietly(pidFile)

        where:
        wait | waiting
        'waitForExit' | { HostExecHandle h, VirtualClock c -> h.waitForExit() }
        'waitForExitOrTimeout' | { HostExecHandle h, VirtualClock c ->
            h.waitForExitOrTimeout(Duration.ofSeconds(30), c)
        }
    }

    // Scenario: lifecycle/subprocess-supervision / A reparented orphan dies with the invocation
    def "FR21: a timed-out wait sweeps the orphan its tree kill could not see"() {
        given: 'a child that leaks a holder out of its own tree, then stalls'
        Path pidFile = work.resolve('holder.pid')
        Process process = grouped(StandIn.process('leaky').toString(), pidFile.toString())
        HostExecHandle handle = new HostExecHandle(process, clock.instant())
        eventually {
            Files.exists(pidFile) && !Files.readString(pidFile).isBlank()
        }

        when:
        ExecHandle.Wait wait = handle.waitForExitOrTimeout(Duration.ofMillis(300), clock)

        then:
        wait instanceof ExecHandle.Wait.TimedOut
        ended(pidFile)

        cleanup:
        killQuietly(pidFile)
    }

    // Scenario: lifecycle/subprocess-supervision / The docker client of a box command is a host process
    def "FR21: the file channel sweeps the docker client's group once its drains are joined"() {
        given: 'a docker client that exits cleanly, naming as its group a real one the spec started'
        Process survivor = GroupLeader.start(work)
        FakeProcess client = FakeProcess.running().leading(survivor.pid())
        client.exit(0)
        ScriptedFileChannelDockerCli docker = new ScriptedFileChannelDockerCli()
        docker.handed = client

        when:
        new ContainerFileChannel(docker, 'k1', '/gnomish/work', '/gnomish/scratch')
                .putFile('note.txt', 'x'.getBytes(StandardCharsets.UTF_8))

        then: 'the group was swept'
        survivor.waitFor(10, TimeUnit.SECONDS)

        cleanup:
        survivor?.destroyForcibly()
    }

    private Process grouped(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command)
        TestChildEnvironment.cleared(builder)
        ProcessLaunch.grouped(work).start(builder, StdinSource.NONE)
    }

    private static boolean ended(Path pidFile) {
        long pid = Long.parseLong(Files.readString(pidFile).trim())
        ProcessHandle.of(pid).ifPresent {
            it.onExit().get(10, TimeUnit.SECONDS)
        }
        !ProcessHandle.of(pid).map { it.isAlive() }.orElse(false)
    }

    private static void killQuietly(Path pidFile) {
        if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
                it.destroyForcibly()
            }
        }
    }

    private static void eventually(Closure<Boolean> condition) {
        long deadline = System.nanoTime() + 10_000_000_000L
        while (!condition.call()) {
            assert System.nanoTime() <deadline: 'timed out waiting for the child to record its holder'
            Thread.sleep(20)
        }
    }
}
