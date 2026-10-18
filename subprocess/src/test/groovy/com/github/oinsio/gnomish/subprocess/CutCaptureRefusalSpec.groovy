package com.github.oinsio.gnomish.subprocess

import java.time.Duration
import spock.lang.Specification

/**
 * FR17, FR19, design D15 of fix-terminal-receipt-convergence as amended 2026-10-17: the one
 * judgement of a cut capture, {@link CutCaptureException#refuses}, that every value reader —
 * {@code CaptureRunner}, {@code GitExec}, {@code CapturedExec} — asks instead of spelling the
 * condition itself (rule of three, {@code manual-sync-pairs.md}), and the one rendering of the
 * note a failed invocation's diagnostics carry, {@link CutCaptureException#withNotice}.
 */
class CutCaptureRefusalSpec extends Specification {

    def "FR17: only a clean exit with code zero whose drain was cut is refused: #termination, exit #exitCode, cut #cut"() {
        expect:
        CutCaptureException.refuses(termination, exitCode, cut) == refused

        where:
        [termination, exitCode, cut] << [
            Termination.values() as List,
            [0, 1, -1, 137],
            [true, false]
        ].combinations()
        refused = termination == Termination.EXITED && exitCode == 0 && cut
    }

    def "FR17: the note follows the failed invocation's own diagnostics, parenthesized"() {
        expect:
        CutCaptureException.withNotice('fatal: refused\n', 'output was cut') == 'fatal: refused\n(output was cut)'
    }

    def "FR17: a single cut stream is named by the same sentence"() {
        given:
        Duration bound = Duration.ofMillis(50)

        expect:
        CutCaptureException.notice(CutCaptureException.CutStreams.STDOUT, bound) ==
                "output was cut: stdout still open at the post-exit bound of ${bound}" +
                ' — a descendant held its pipes after the command exited'
    }
}
