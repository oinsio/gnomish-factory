package com.github.oinsio.gnomish.sandbox;

import com.github.oinsio.gnomish.subprocess.CutCaptureException;
import com.github.oinsio.gnomish.subprocess.Drain;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.time.Duration;

/**
 * One exec'd command captured whole: the exit code and everything it wrote —
 * the shared shape for every caller that runs a short command through {@link
 * TaskExecutionEnvironment#exec(ExecCommand)} and wants its full output rather
 * than a stream (an in-box service commit, a salvage command, a self-check
 * probe).
 *
 * <p>The output is read on the {@code :subprocess} module's {@link Drain}
 * <em>concurrently</em> with the supervised wait, and joined only after the wait
 * has resolved (design D2 of bound-subprocess-commands) — with the handle's own
 * {@link ExecHandle#postExitBound()}, never a constant of this class, and
 * interruptibly (design D17 of fix-terminal-receipt-convergence). Reading the
 * pipe to EOF on the calling thread first is the reproduced hang: a hung in-box
 * command holds its stdout open and the wait is never reached. Joining without a
 * bound after the wait is the same hang one step later: a descendant that
 * inherited the pipe holds it after the command exited.
 *
 * <p>The drain reads the handle's {@link ExecHandle#output()} as a {@code ChildOutput}, which
 * never parks in the pipe (design D1, D2 of make-pipe-reads-non-parking): it ends on its own as
 * soon as it sees the exit with nothing pending, so a descendant that holds the pipe in silence
 * leaves the capture whole. Only a holder that keeps writing keeps it open to the bound, where the
 * join stops it; no pipe is closed — the JDK's reaper closes it at the exit (D3, D4).
 *
 * <p>A drain still open at the bound is judged by {@link
 * CutCaptureException#refuses}, the one judgement every value reader asks (design
 * D15 as amended 2026-10-17): after a clean exit with code zero the read fails —
 * an {@link UncheckedIOException} in the words of {@link CutCaptureException#notice},
 * the shape a broken stream already has, which every caller classifies — since a
 * prefix returned as the whole output would be taken for the command's answer; on
 * a non-zero exit the partial output stays readable, the cut named at its end.
 *
 * <p>A wait or a join cut short by an interrupt is reported by name — an {@link
 * InterruptedIOException} inside the thrown {@link UncheckedIOException} — with
 * the interrupt flag left set, never as the killed process's exit code, which a
 * caller could not tell from a code the command genuinely chose (FR11). The
 * classification reads the flag the supervised wait or the drain's join restores;
 * an external interrupt landing in the instant after a clean exit is
 * conservatively classified the same way, which at worst re-runs work that did
 * complete.
 *
 * <p>The output is {@link UntrustedText}: a command run inside a box speaks for
 * whatever the gnome put there, so what it wrote is attacker-influenced from the
 * moment it crosses back over the box boundary — which is where {@link #of} mints
 * it (design D3 of type-untrusted-text). It reaches logs, reports and {@code
 * task.json} through an exit; the probes and self-checks that read it for an
 * answer read it through {@code forParsing()}.
 *
 * <p>Implements FR2, FR11 of bound-subprocess-commands; FR1, FR7 of
 * type-untrusted-text; FR17, FR19 of fix-terminal-receipt-convergence; FR3, FR4, FR5 of
 * make-pipe-reads-non-parking.
 *
 * @param exitCode the exit code the wait reported; meaningful only because an
 *     interrupted wait never reaches a {@code CapturedExec} at all
 * @param output everything the command wrote to the captured stream, as UTF-8;
 *     on a failed command whose stream was cut, the prefix followed by the note
 */
public record CapturedExec(int exitCode, UntrustedText output) {

    /**
     * Runs the capture over an already-started handle: starts the drain, waits
     * through the handle's supervised wait, and joins the drain with the
     * handle's post-exit bound once the wait has resolved.
     *
     * @param handle the started command's handle; its output is consumed, and stopped when cut
     * @param what the operation for failure messages, e.g. {@code "in-box state commit"}
     * @return the exit code and the complete captured output, minted as in-container
     *     text; never null
     * @throws UncheckedIOException if the wait or the join was interrupted (cause {@link
     *     InterruptedIOException}, flag left set), the output stream broke mid-read, or
     *     the command exited zero while its output was still held at the bound
     */
    public static CapturedExec of(ExecHandle handle, String what) {
        Drain drain = Drain.start(handle.output(), "exec-capture");
        int exitCode = handle.waitForExit();
        Duration bound = handle.postExitBound();
        boolean cut = Drain.joinAll(bound, drain);
        if (Thread.currentThread().isInterrupted()) {
            // The supervised wait killed the tree and restored the flag, or the join restored it;
            // either way the join stopped a drain still open, so the drain thread ends within a tick.
            throw new UncheckedIOException(new InterruptedIOException(
                    what + " did not complete: the wait was interrupted and the process tree was killed"));
        }
        IOException broken = drain.failure();
        if (!cut && broken != null) {
            // A cut drain's failure is the stop that ended it, not a broken stream.
            throw new UncheckedIOException("could not read " + what + " output", broken);
        }
        String output = drain.captured();
        if (cut) {
            String notice = CutCaptureException.notice(CutCaptureException.CutStreams.STDOUT, bound);
            if (CutCaptureException.refuses(Termination.EXITED, exitCode, cut)) {
                throw new UncheckedIOException(what + " " + notice, new IOException(notice));
            }
            output = CutCaptureException.withNotice(output, notice);
        }
        return new CapturedExec(exitCode, UntrustedText.container(output));
    }
}
