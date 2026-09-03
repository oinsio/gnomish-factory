package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.logtext.ShutdownPhase
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9 of harden-logging-observability: the bound-fired report a git command owes an operator says
 * which of the two things happened. An interrupt during the daemon's own stop is the stop doing its
 * job — every in-flight git command gets one, and a line that reads like an unexplained abort turns
 * a clean shutdown into a wall of warnings that name no fault.
 *
 * <p>Driven by a stalling git stand-in and a pre-interrupted caller (the deterministic path
 * {@code ProcessSupervisorInterruptSpec} owns), so no timing race decides the outcome.
 */
class GitProcessRunnerShutdownReportSpec extends Specification {

    @TempDir
    Path tempDir

    def cleanup() {
        ShutdownPhase.reset()
        Thread.interrupted()
    }

    private GitProcessRunner stallingRunner() {
        Path bin = tempDir.resolve('stalling-git')
        Files.writeString(bin, "#!/bin/sh\nsleep 600\n")
        bin.toFile().setExecutable(true)
        new GitProcessRunner(bin.toString())
    }

    def "FR9: outside the shutdown phase the interrupt is reported as an unexplained one"() {
        given:
        def runner = stallingRunner()
        def capture = LogCaptureSupport.attach(GitProcessRunner)

        when:
        Thread.currentThread().interrupt()
        def result = runner.run(tempDir, 'ls-remote', 'origin')

        then:
        result.termination() == Termination.INTERRUPTED
        capture.list.size() == 1
        capture.list[0].level == Level.WARN
        capture.list[0].formattedMessage.startsWith(OperatorEvent.GIT_COMMAND_KILLED.head())
        capture.list[0].formattedMessage.contains('git command interrupted and its process tree was killed')
        !capture.list[0].formattedMessage.contains('daemon shutdown')

        cleanup:
        capture.detach()
    }

    def "FR9: during the shutdown phase the same interrupt is attributed to the stop"() {
        given:
        def runner = stallingRunner()
        def capture = LogCaptureSupport.attach(GitProcessRunner)
        ShutdownPhase.begin()

        when:
        Thread.currentThread().interrupt()
        def result = runner.run(tempDir, 'ls-remote', 'origin')

        then: 'one line, no stack, and it names the stop rather than a fault'
        result.termination() == Termination.INTERRUPTED
        capture.list.size() == 1
        capture.list[0].level == Level.WARN
        capture.list[0].throwableProxy == null
        capture.list[0].formattedMessage.startsWith(OperatorEvent.GIT_COMMAND_KILLED.head())
        capture.list[0].formattedMessage.contains('git command interrupted by the daemon shutdown')
        capture.list[0].formattedMessage.contains('subcommand=ls-remote')

        cleanup:
        capture.detach()
    }
}
