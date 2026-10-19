package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.subprocess.ChildOutput;
import com.github.oinsio.gnomish.subprocess.CutCaptureException;
import com.github.oinsio.gnomish.subprocess.Drain;
import java.time.Duration;

/**
 * A command check's merged stdout/stderr stream, read by the one drain ({@link Drain} of {@code
 * :subprocess}) while the command still runs, into the bounded tail {@link TailLines} keeps (design
 * D6, FR7 of add-manual-run; design D17 of fix-terminal-receipt-convergence).
 *
 * <p>Reading the stream to its end on the calling thread — the shape this class once replaced — let
 * a hung check hang the run: a command that never exits never ends its stdout. The drain therefore
 * starts with the process and is joined once the wait has resolved, bounded by the post-exit bound
 * of the handle the check ran on — the bound its supervisor derives, never a constant of the check
 * runner — whichever way the command ended. The drain reads the child's output through its {@link
 * ChildOutput}, never parking in a read, and ends on its own as soon as it sees the exit with
 * nothing pending, so a process that escaped the tree and holds the pipe in silence leaves the tail
 * complete and unflagged (design D2, D4 of make-pipe-reads-non-parking). Only a holder that keeps
 * writing every tick after the exit keeps the drain open to the bound; it is then stopped, and the
 * tail names the cut in the words of {@link CutCaptureException#notice}.
 *
 * <p>The tail is diagnostics, never a value: a check's verdict is its exit code. So a cut is never a
 * refusal here; it rides the tail through {@link CutCaptureException#withNotice}, which a failed
 * check reports and a passing check discards (design D15 as amended 2026-10-17).
 *
 * <p>Implements FR7, D6 of add-manual-run; FR2, FR12, UX4 of bound-subprocess-commands; FR17, FR19
 * of fix-terminal-receipt-convergence; FR2, FR4 of make-pipe-reads-non-parking.
 */
final class BoundedTail {

    private final Drain drain;
    private final TailLines lines;

    private BoundedTail(Drain drain, TailLines lines) {
        this.drain = drain;
        this.lines = lines;
    }

    /**
     * Starts draining {@code output} into a bounded tail on a named virtual thread.
     *
     * @param output the process's merged output
     * @return the live tail; never null
     */
    static BoundedTail start(ChildOutput output) {
        TailLines lines = new TailLines();
        return new BoundedTail(Drain.into(output, "check-output-tail", lines), lines);
    }

    /**
     * Waits for the drain for at most {@code handle}'s post-exit bound and returns the tail it
     * captured. An interrupted caller gets the prefix captured so far at once, its flag kept.
     *
     * @param handle the handle the check ran on, whose supervisor's bound the join waits
     * @return the captured tail, or the prefix captured so far with the cut named; never null
     */
    String join(ExecHandle handle) {
        Duration bound = handle.postExitBound();
        boolean cut = Drain.joinAll(bound, drain);
        String tail = lines.snapshot();
        if (!cut) {
            return tail;
        }
        return CutCaptureException.withNotice(
                tail, CutCaptureException.notice(CutCaptureException.CutStreams.STDOUT, bound));
    }
}
