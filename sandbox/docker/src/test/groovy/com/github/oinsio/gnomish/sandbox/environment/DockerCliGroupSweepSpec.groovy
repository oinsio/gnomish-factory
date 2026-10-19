package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.subprocess.CaptureRunner
import com.github.oinsio.gnomish.subprocess.CutCaptureException
import com.github.oinsio.gnomish.subprocess.GroupSweep
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, NFR-O3, design D19 of fix-terminal-receipt-convergence: a docker management command's
 * capture is swept by the capture runner, and {@link DockerCommandReport} says one DEBUG line,
 * through {@link HostSweep}, for a sweep that found a survivor — on a result and on a refused cut —
 * and nothing for an empty group. The survivor is a real {@link GroupLeader} named as the pid of
 * the {@link FakeProcess} the CLI is handed. The refused cut is made by a {@link WritingHolder} on
 * stdout: a drain that never parks reaches the bound only while a holder keeps writing after the
 * exit (design D4 of make-pipe-reads-non-parking).
 */
class DockerCliGroupSweepSpec extends Specification {

    @TempDir
    Path work

    def "FR21, NFR-O3: a management command's capture is swept, and a survivor gets its DEBUG line"() {
        given: 'docker exits cleanly, naming as its group a real one the spec started'
        Process survivor = GroupLeader.start(work)
        FakeProcess client = FakeProcess.running().leading(survivor.pid())
        client.writeStdout('ok')
        client.exit(0)
        DockerCli cli = new DockerCli('docker', Duration.ofSeconds(30), new CaptureRunner(new ProcessSupervisor(), { ProcessBuilder builder, StdinSource stdin ->
            client
        } as ProcessLaunch))

        when:
        List<ILoggingEvent> lines = LogCaptureSupport.capture(HostSweep, Level.DEBUG) {
            cli.run(['inspect', 'box'])
        }

        then:
        survivor.waitFor(10, TimeUnit.SECONDS)
        // The round count is not asserted: a killed member the JVM's reaper has not reaped yet
        // still answers the next probe (ProcessGroupSweep), so it is a scheduling fact, as in
        // GitProcessRunnerGroupSweepSpec.
        lines.size() == 1
        lines[0].level == Level.DEBUG
        lines[0].formattedMessage ==~ /docker inspect: process group swept after the exit: rounds=[1-5], cleared=(true|false)/

        cleanup:
        survivor?.destroyForcibly()
    }

    def "FR21, NFR-O3: a refused cut of a management command is swept first, its survivor named at DEBUG"() {
        given: 'docker exits cleanly while a holder keeps its pipes and writes to stdout, naming as its group a real one the spec started'
        Process survivor = GroupLeader.start(work)
        FakeProcess client = FakeProcess.running().leading(survivor.pid())
        client.holdOpen()
        WritingHolder.on(client.stdoutPipe())
        client.exit(0)
        DockerCli cli = new DockerCli('docker', Duration.ofSeconds(30), new CaptureRunner(
                        new ProcessSupervisor(Duration.ofMillis(50)), { ProcessBuilder builder, StdinSource stdin ->
                            client
                        } as ProcessLaunch))

        when:
        CutCaptureException refusal = null
        List<ILoggingEvent> lines = LogCaptureSupport.capture(HostSweep, Level.DEBUG) {
            try {
                cli.run(['inspect', 'box'])
            } catch (CutCaptureException e) {
                // the refusal's WARN is DockerCliTruncatedCaptureSpec's subject
                refusal = e
            }
        }

        then: 'the capture was refused as a cut'
        refusal != null

        and: 'the group was swept first, its survivor named at DEBUG'
        survivor.waitFor(10, TimeUnit.SECONDS)
        lines.size() == 1
        lines[0].formattedMessage.startsWith('docker inspect: process group swept after the exit: rounds=')

        cleanup:
        client.release()
        survivor?.destroyForcibly()
    }

    def "NFR-O3: a sweep that found the group empty says nothing"() {
        expect:
        LogCaptureSupport.capture(HostSweep, Level.DEBUG) {
            HostSweep.report(GroupSweep.NOTHING, 'docker inspect')
        }.isEmpty()
    }
}
