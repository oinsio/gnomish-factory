package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Renders a {@link Throwable}'s complete stack trace so a captured failure can be preserved
 * verbatim in an escalation or abort report (NFR-O1). The engine surfaces
 * infrastructure failures as data, not exceptions (design D1), so every capture point that
 * turns a throw into a report — the executor throw ({@link RoundExecution}), a persist throw
 * ({@link AttemptJournal}) and a check-adapter throw ({@link VerifyOrchestrator}) — renders
 * its cause through this one helper rather than duplicating the plumbing.
 *
 * <p>A stateless, package-private static-only utility with no instances (NFR-R1).
 *
 * <p>Implements NFR-O1 of add-stage-engine.
 */
final class StackTraces {

    /** What the tail is, spelled once by its carrier (the wrapper's message uses it too). */
    private static final String TAIL_LABEL = ExecutorFailure.STDERR_TAIL_LABEL;

    /**
     * Never instantiated: this is a static-only utility. Kept as an explicit private
     * constructor that throws so the no-instances contract is enforced and PIT does not
     * flag an unreachable default constructor as a surviving mutation.
     */
    private StackTraces() {
        throw new AssertionError("no instances");
    }

    /**
     * Renders {@code ex}'s complete stack trace — message and frames — exactly as
     * {@link Throwable#printStackTrace()} would, so the text can be preserved in a report's
     * cause (NFR-O1).
     *
     * <p>The result is untrusted text (design D4 of type-untrusted-text): a rendered chain quotes
     * every message in it, and the failures this helper renders come from machinery the factory
     * drove out of process — a git or agent subprocess, a check adapter's command — so a hostile
     * line of that machinery's output reaches the report through the throwable's message. The
     * provenance is that operation's, which for all three capture points is the subprocess plane
     * (design D5's fold row).
     *
     * <p>Implements NFR-O1 of add-stage-engine; FR1 of type-untrusted-text.
     *
     * @param ex the throwable whose stack trace is rendered; never null
     * @return the fully rendered stack trace, carried; never null
     */
    static UntrustedText render(Throwable ex) {
        return UntrustedText.subprocess(trace(ex));
    }

    /**
     * Renders {@code ex}'s stack trace followed by the stderr tail of the round's process, so the
     * report carries the one diagnostic a CLI that died leaves behind (design D18 of
     * fix-terminal-receipt-convergence, as amended 2026-10-17). The tail leaves its carrier
     * through the log exit before it joins the trace — one inert line of its last characters,
     * where the error is — so no byte of it is re-read raw. A blank tail renders exactly as
     * {@link #render(Throwable)}.
     *
     * <p>Implements FR20 of fix-terminal-receipt-convergence.
     *
     * @param ex the throwable whose stack trace is rendered; never null
     * @param stderrTail the round process's stderr tail; never null, possibly blank
     * @return the rendered trace and tail, carried; never null
     */
    static UntrustedText render(Throwable ex, UntrustedText stderrTail) {
        if (stderrTail.isBlank()) {
            return render(ex);
        }
        return UntrustedText.subprocess(trace(ex) + TAIL_LABEL + stderrTail.forLog() + System.lineSeparator());
    }

    /**
     * The ending of a log line that quotes the round process's stderr tail through the log exit;
     * empty when the tail is blank, so the line of a failure without one is unchanged.
     *
     * @param stderrTail the round process's stderr tail; never null, possibly blank
     * @return {@code "; "}, the label and the rendered tail, or the empty string; never null
     */
    static String logSuffix(UntrustedText stderrTail) {
        return stderrTail.isBlank() ? "" : "; " + TAIL_LABEL + stderrTail.forLog();
    }

    private static String trace(Throwable ex) {
        var sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
