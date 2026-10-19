package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.GroupLeader
import com.github.oinsio.gnomish.sandbox.environment.WritingHolder
import com.github.oinsio.gnomish.subprocess.CaptureRunner
import com.github.oinsio.gnomish.subprocess.CutCaptureException
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
 * NFR-O3, FR21, design D19 of fix-terminal-receipt-convergence: the capture runner sweeps git's
 * process group after every invocation, and the git runner says one DEBUG line for a sweep that
 * found a survivor — on a result and on a refused cut alike — and nothing for an empty group. The
 * survivor is a real {@link GroupLeader}, named as the pid of the {@link FakeProcess} the runner
 * is handed, so the sweep the code under test runs reaches it and nothing else. The refused cut is
 * made by a {@link WritingHolder} on stdout: a drain that never parks reaches the bound only while a
 * holder keeps writing after the exit (design D4 of make-pipe-reads-non-parking).
 */
class GitProcessRunnerGroupSweepSpec extends Specification {

    @TempDir
    Path cwd

    def "NFR-O3: a sweep that found a survivor after #shape says one DEBUG line naming the subcommand"() {
        given: 'git exits, naming as its group a real one the spec started'
        Process survivor = GroupLeader.start(cwd)
        FakeProcess git = FakeProcess.running().leading(survivor.pid())
        if (held) {
            git.holdOpen()
            WritingHolder.on(git.stdoutPipe())
        }
        git.exit(0)

        when:
        boolean refused = false
        List<ILoggingEvent> lines = LogCaptureSupport.capture(GitProcessRunner, Level.DEBUG) {
            try {
                runnerOver(git).run(cwd, 'rev-parse', 'HEAD')
            } catch (CutCaptureException ignored) {
                // the refusal's WARN is the cut feature's subject; the sweep's line is this one's
                refused = true
            }
        }

        then: 'the shape is the one named: a refusal exactly when a writing holder kept the pipe'
        refused == held

        and: 'the group was swept, and one DEBUG line says so'
        survivor.waitFor(10, TimeUnit.SECONDS)
        List<ILoggingEvent> swept = lines.findAll {
            it.formattedMessage.contains('process group swept')
        }
        swept.size() == 1
        swept[0].level == Level.DEBUG
        swept[0].formattedMessage.startsWith('git rev-parse: process group swept after the exit: rounds=')

        cleanup:
        git.release()
        survivor?.destroyForcibly()

        where:
        shape | held
        'a clean exit' | false
        'a refused cut' | true
    }

    def "NFR-O3: a sweep that found the group empty says nothing"() {
        given: 'git exits, its pid naming no group'
        FakeProcess git = FakeProcess.running()
        git.exit(0)

        expect:
        LogCaptureSupport.capture(GitProcessRunner, Level.DEBUG) {
            runnerOver(git).run(cwd, 'rev-parse', 'HEAD')
        }.findAll {
            it.formattedMessage.contains('process group swept')
        }.isEmpty()
    }

    private static GitProcessRunner runnerOver(FakeProcess process) {
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            process
        } as ProcessLaunch
        new GitProcessRunner('git', Duration.ofSeconds(30),
                new CaptureRunner(new ProcessSupervisor(Duration.ofMillis(50)), launch))
    }
}
