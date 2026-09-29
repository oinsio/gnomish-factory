package com.github.oinsio.gnomish.app;

import org.springframework.boot.SpringBootExceptionReporter;

/**
 * Claims, for Spring Boot's failure reporting, every failure {@link RunExceptionReporting} has
 * already reported to the operator. A command ends with a classified exception on purpose — it is
 * how the exit code reaches {@link RunExitCodeMapper} — so by the time Spring Boot sees it the
 * operator already has the one line it deserves. Without this claim Spring Boot logs the same
 * exception again as an ERROR "Application run failed" record with its full stack trace, which the
 * logging configuration copies to stdout, stderr and the log file: a mistyped option became four
 * messages and a 33-frame trace (NFR-O1, FR16 of fix-operator-blockers).
 *
 * <p>An unclassified fault is not claimed: it keeps Spring Boot's record, as does any failure
 * raised before the runner starts (a context that fails to refresh). Registered in {@code
 * META-INF/spring.factories} of the composition root; Spring Boot instantiates it reflectively,
 * hence the public no-argument constructor.
 *
 * <p>Implements NFR-O1, FR16 of fix-operator-blockers.
 */
public final class ReportedFailureExceptionReporter implements SpringBootExceptionReporter {

    /** Instantiated by Spring Boot's factory loader. */
    public ReportedFailureExceptionReporter() {}

    @Override
    public boolean reportException(Throwable failure) {
        return RunExceptionReporting.calmLine(failure) != null;
    }
}
