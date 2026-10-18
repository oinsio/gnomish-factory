package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.time.Duration
import spock.lang.Specification

/**
 * FR17, NFR-O3, design D15 of fix-terminal-receipt-convergence as amended 2026-10-17: the one
 * sentence a cut capture is named by names exactly the streams the post-exit bound cut, and the
 * bound — the words both refusing readers, {@code CaptureRunner} and {@code GitExec}, take from
 * {@link CutCaptureException#notice}. Drains over a {@link FakeProcess} whose pipes the spec holds
 * or releases itself (design D16).
 */
class CutCaptureNoticeSpec extends Specification {

    private static final Duration BOUND = Duration.ofMillis(50)

    def "FR17: the notice names the streams still open at the bound: #phrase"() {
        given: 'a fake whose pipes are held, then the ones the row names released'
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        process.exit(0)
        if (!stdoutHeld) {
            process.releaseStdout()
        }
        if (!stderrHeld) {
            process.releaseStderr()
        }
        Drain stdout = Drain.start(process.inputStream, 'notice-stdout')
        Drain stderr = Drain.start(process.errorStream, 'notice-stderr')

        when:
        boolean cut = Drain.joinAll(BOUND, stdout, stderr)

        then:
        cut
        CutCaptureException.notice(stdout, stderr, BOUND) ==
                "output was cut: ${phrase} still open at the post-exit bound of ${BOUND}" +
                ' — a descendant held its pipes after the command exited'

        cleanup:
        process.release()

        where:
        stdoutHeld | stderrHeld | phrase
        true | true | 'stdout and stderr'
        true | false | 'stdout'
        false | true | 'stderr'
    }
}
