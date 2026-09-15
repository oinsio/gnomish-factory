package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleClosedException;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.io.IOException;
import org.slf4j.Logger;

/**
 * The short-line-instead-of-stack-trace reporting {@code ManualRunRunner#run} (module {@code
 * :bootstrap}, not linkable from here — this module sits below it in the layer stack) wraps its
 * drive call in (UX3): each known exception family prints a calm, single line through the
 * error console — the console owner bound to {@code stderr} by the composition root — (or
 * nothing, when the callee already printed one) before rethrowing unchanged, so {@link
 * RunExitCodeMapper} still maps the exit code. Split out of that runner purely to keep it within
 * the project's file-size target (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR1, FR2, FR4, FR9, FR12, NFR-O1, UX3 of add-manual-run; FR5 of
 * harden-untrusted-text-sinks.
 */
final class RunExceptionReporting {

    private RunExceptionReporting() {}

    /** A block that may throw the same checked/unchecked exceptions {@link #run} classifies. */
    @FunctionalInterface
    interface ThrowingAction {
        void run() throws IOException, InterruptedException;
    }

    /**
     * Runs {@code action}, reporting and rethrowing any exception per UX3's classification.
     *
     * @param action the drive call to wrap; never null
     * @param log the logger the generic-fallback branch warns to; never null
     * @param errorConsole the console owner bound to {@code stderr}; never null. Every line here
     *     goes out on its human path: an exception message can carry subprocess output or a
     *     tracker string, so the operator sees any escape sequence as text (FR5, FR6 of
     *     harden-untrusted-text-sinks)
     * @throws IOException propagated unchanged from {@code action}
     * @throws InterruptedException propagated unchanged from {@code action} — a {@code gnomish
     *     serve} feed loop interrupted mid-run (FR2 of add-factory-serve); not otherwise
     *     classified, since interruption is a lifecycle signal, not a reportable failure
     */
    static void run(ThrowingAction action, Logger log, ConsoleIO errorConsole)
            throws IOException, InterruptedException {
        try {
            action.run();
        } catch (UsageException
                | PipelineLoadFailedException
                | InternalErrorException
                | DefaultBranchUnboundException ex) {
            errorConsole.print(ex.getMessage() + ConsoleIO.LINE_END);
            throw ex;
        } catch (InputExhaustedException | ConsoleClosedException ex) {
            errorConsole.print("Input exhausted — stopping." + ConsoleIO.LINE_END);
            throw ex;
        } catch (TaskNotFoundException ex) { // UX3, D15: calm message already on the console
            throw ex;
        } catch (BranchShapeRefusedException ex) { // FR16: diagnosis already on the console
            throw ex;
        } catch (UnsupportedStateFileVersionException ex) { // FR4: clean refusal, no WARN/stack trace
            errorConsole.print(ex.getMessage() + ConsoleIO.LINE_END);
            throw ex;
        } catch (RuntimeException | IOException ex) {
            log.warn(
                    OperatorEvent.RUN_UNHANDLED_EXCEPTION.head() + "gnomish run terminated with an unhandled exception",
                    ex);
            errorConsole.print("gnomish run failed: " + ex.getMessage() + ConsoleIO.LINE_END);
            throw ex;
        }
    }
}
