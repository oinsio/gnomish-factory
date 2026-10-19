package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.time.Duration
import spock.lang.Specification

/**
 * FR17, NFR-O3, design D15 of fix-terminal-receipt-convergence as amended 2026-10-17: the one
 * sentence a cut capture is named by names exactly the streams the post-exit bound cut, and the
 * bound — the words both refusing readers, {@code CaptureRunner} and {@code GitExec}, take from
 * {@link CutCaptureException#notice}. Drains over {@link FakeProcess}es (design D16): a stream
 * the bound cuts is the output of a child the spec keeps alive, one that ended is the output of a
 * child that exited — a held pipe after the exit no longer keeps a drain open (design D2 of
 * make-pipe-reads-non-parking).
 */
class CutCaptureNoticeSpec extends Specification {

    private static final Duration BOUND = Duration.ofMillis(50)

    def "FR17: the notice names the streams still open at the bound: #phrase"() {
        given: 'a live child for each stream the row names held, an exited one for the others'
        FakeProcess held = FakeProcess.running()
        FakeProcess ended = new FakeProcess()
        Drain stdout = Drain.start(ChildOutput.stdout(stdoutHeld ? held : ended), 'notice-stdout')
        Drain stderr = Drain.start(ChildOutput.stderr(stderrHeld ? held : ended), 'notice-stderr')

        when:
        boolean cut = Drain.joinAll(BOUND, stdout, stderr)

        then:
        cut
        CutCaptureException.notice(stdout, stderr, BOUND) ==
                "output was cut: ${phrase} still open at the post-exit bound of ${BOUND}" +
                ' — a descendant held its pipes after the command exited'

        cleanup:
        held.exit(0)

        where:
        stdoutHeld | stderrHeld | phrase
        true | true | 'stdout and stderr'
        true | false | 'stdout'
        false | true | 'stderr'
    }
}
