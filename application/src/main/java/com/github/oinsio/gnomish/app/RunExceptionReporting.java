package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.GitVersionRefusedException;
import com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;
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
 * harden-untrusted-text-sinks; NFR-O1, FR16 of fix-operator-blockers; FR7 of add-project-registry;
 * FR4, NFR-O1 of make-run-headless.
 */
final class RunExceptionReporting {

    /** Families whose own message is the operator's line. */
    private static final List<Class<? extends Throwable>> PRINTS_OWN_MESSAGE = List.of(
            UsageException.class,
            PipelineLoadFailedException.class,
            InternalErrorException.class,
            DefaultBranchUnboundException.class,
            GitVersionRefusedException.class, // UX2 of own-git-transfer-argv: a precondition, not a crash
            UnsupportedStateFileVersionException.class, // FR4: clean refusal, no WARN/stack trace
            ConfigurationViolationsException.class); // FR7 of add-project-registry: the loader's report

    /** Families whose outcome the callee already put on the console. */
    private static final List<Class<? extends Throwable>> ALREADY_REPORTED = List.of(
            TaskNotFoundException.class, // UX3, D15: calm message already on the console
            BranchShapeRefusedException.class, // FR16: diagnosis already on the console
            TakeExitCodeException.class, // D16 of add-tracker-port: exit-code carriers, outcome
            ServeExitCodeException.class, // already reported by the command itself
            RunParkedException.class, // FR1, FR2 of make-run-headless: the stop render is on stdout
            DecisionRequiredException.class); // FR4 of make-run-headless: the question is restated on stdout

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
        } catch (RuntimeException | IOException ex) {
            // NFR-O1 of make-run-headless: the resume command reaches the log as well as stdout, so a
            // reader of either finds it — for a fresh stop and for a resume refused without a decision.
            if (ex instanceof RunParkedException parked) {
                log.info("{}", parked.stopRecord());
            } else if (ex instanceof DecisionRequiredException required) {
                log.info("{}", required.stopRecord());
            }
            String line = calmLine(ex);
            if (line == null) {
                log.warn(
                        OperatorEvent.RUN_UNHANDLED_EXCEPTION.head()
                                + "gnomish run terminated with an unhandled exception",
                        ex);
                errorConsole.print("gnomish run failed: " + ex.getMessage() + ConsoleIO.LINE_END);
            } else if (!line.isEmpty()) {
                errorConsole.print(line + ConsoleIO.LINE_END);
            }
            throw ex;
        }
    }

    /**
     * The one classification of a failure that ends a command: the single line the operator gets
     * for it, the empty string when the callee already reported it, or {@code null} for an
     * unclassified fault, which takes the generic WARN-backed fallback. {@link
     * ReportedFailureExceptionReporter} reads the same answer, so a failure reported here is never
     * reported a second time by Spring Boot's own "Application run failed" record (NFR-O1,
     * FR16 of fix-operator-blockers).
     */
    static @Nullable String calmLine(Throwable failure) {
        if (isAny(failure, PRINTS_OWN_MESSAGE)) {
            return String.valueOf(failure.getMessage());
        }
        if (isAny(failure, ALREADY_REPORTED)) {
            return "";
        }
        return null;
    }

    private static boolean isAny(Throwable failure, List<Class<? extends Throwable>> families) {
        return families.stream().anyMatch(family -> family.isInstance(failure));
    }
}
