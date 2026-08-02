package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException;
import com.github.oinsio.gnomish.adapter.git.state.UnsupportedStateFileVersionException;
import java.io.IOException;
import org.slf4j.Logger;

/**
 * The short-line-instead-of-stack-trace reporting {@link ManualRunRunner#run} wraps its drive call
 * in (UX3): each known exception family prints a calm, single line to {@code stderr} (or nothing,
 * when the callee already printed one) before rethrowing unchanged, so {@link RunExitCodeMapper}
 * still maps the exit code. Split out of {@link ManualRunRunner} purely to keep that class within
 * the project's file-size target (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR1, FR2, FR4, FR9, FR12, NFR-O1, UX3 of add-manual-run.
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
     * @throws IOException propagated unchanged from {@code action}
     * @throws InterruptedException propagated unchanged from {@code action} — a {@code gnomish
     *     serve} feed loop interrupted mid-run (FR2 of add-factory-serve); not otherwise
     *     classified, since interruption is a lifecycle signal, not a reportable failure
     */
    static void run(ThrowingAction action, Logger log) throws IOException, InterruptedException {
        try {
            action.run();
        } catch (UsageException | PipelineLoadFailedException | InternalErrorException ex) {
            System.err.println(ex.getMessage());
            throw ex;
        } catch (InputExhaustedException | ConsoleClosedException ex) {
            System.err.println("Input exhausted — stopping.");
            throw ex;
        } catch (TaskNotFoundException ex) { // UX3, D15: calm message already on System.out
            throw ex;
        } catch (UnsupportedStateFileVersionException ex) { // FR4: clean refusal, no WARN/stack trace
            System.err.println(ex.getMessage());
            throw ex;
        } catch (RuntimeException | IOException ex) {
            log.warn("gnomish run terminated with an unhandled exception", ex);
            System.err.println("gnomish run failed: " + ex.getMessage());
            throw ex;
        }
    }
}
