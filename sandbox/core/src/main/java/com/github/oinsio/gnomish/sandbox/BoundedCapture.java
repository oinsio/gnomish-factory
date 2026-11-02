package com.github.oinsio.gnomish.sandbox;

import com.github.oinsio.gnomish.subprocess.Drain;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.time.InstantSource;

/**
 * One exec'd command read under a bound: its output captured up to a cap, read on the {@code
 * :subprocess} module's {@link Drain} concurrently with the handle's bounded wait ({@link
 * ExecHandle#waitForExitOrTimeout}), and the drain joined after the wait has resolved with the
 * handle's own {@link ExecHandle#postExitBound()} — the one sequence of every short, bounded read of
 * a process through {@link TaskExecutionEnvironment#exec(ExecCommand)} (a program's {@code
 * describe}, a non-streaming program run, git's view of a working copy; design D9 of
 * define-executor-contract). The caller maps the {@link ExecHandle.Wait} to its own outcome and asks
 * the handle for the exit code on {@link ExecHandle.Wait.Exited}.
 *
 * <p>Where {@link CapturedExec} waits without a bound of its own and judges a cut capture, this owner
 * judges nothing: it reports whether the capture is {@link #incomplete()} — cut at the post-exit
 * bound by a descendant still writing, or over the cap — and the caller decides what an incomplete
 * capture means (a log keeps its prefix; a view that must be whole refuses it).
 *
 * <p>The output is minted at the point of capture ({@code .claude/rules/logging.md}, "A new capture
 * source mints at the point of capture"): a command's output is attacker-influenced, so it leaves
 * this owner as a {@link UntrustedText#subprocess} carrier, never a {@code String}, and each caller
 * takes the exit its plane needs — the log exit for a log, {@code forParsing()} in an
 * {@code @UntrustedParser} for a view turned into a value.
 *
 * <p>Implements FR4, FR5, FR11, NFR-R2 of define-executor-contract.
 *
 * @param ended how the bounded wait ended; never null
 * @param output what the command wrote to the captured stream, as UTF-8, at most the cap, in the
 *     subprocess family; never null
 * @param incomplete whether {@code output} may be a prefix: the drain was still open at the post-exit
 *     bound, or the command wrote more than the cap
 */
public record BoundedCapture(ExecHandle.Wait ended, UntrustedText output, boolean incomplete) {

    /**
     * Runs the capture over an already-started handle.
     *
     * @param handle the started command's handle; its output is consumed
     * @param name the drain thread's name, e.g. {@code "describe-output"}; never blank
     * @param capBytes the most bytes of output kept; positive
     * @param bound how long the command may run before its tree is killed; never null, never negative
     * @param clock the read-time source of the bounded wait; never null
     * @return the wait's outcome and the captured output; never null
     */
    public static BoundedCapture of(
            ExecHandle handle, String name, long capBytes, Duration bound, InstantSource clock) {
        Drain drain = Drain.capped(handle.output(), name, capBytes);
        ExecHandle.Wait ended = handle.waitForExitOrTimeout(bound, clock);
        boolean cut = Drain.joinAll(handle.postExitBound(), drain);
        return new BoundedCapture(ended, UntrustedText.subprocess(drain.captured()), cut || drain.overflowed());
    }
}
