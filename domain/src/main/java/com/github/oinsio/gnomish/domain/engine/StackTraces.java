package com.github.oinsio.gnomish.domain.engine;

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
        var sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return UntrustedText.subprocess(sw.toString());
    }
}
