package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.console.ConsoleClosedException
import spock.lang.Specification

/**
 * NFR-O1, FR16 of fix-operator-blockers: Spring Boot's own failure record is suppressed exactly
 * for the failures {@link RunExceptionReporting} already reported, and kept for everything else.
 */
class ReportedFailureExceptionReporterSpec extends Specification {

    def reporter = new ReportedFailureExceptionReporter()

    def "FR16: claims a failure the runner already reported (#failure.class.simpleName)"() {
        expect:
        reporter.reportException(failure)

        where:
        failure << [
            new UsageException('unknown option --x'),
            new TaskNotFoundException('PROJ-1'),
            new TakeExitCodeException(0),
            new ServeExitCodeException(1),
            new ConsoleClosedException(),
            // FR7 of add-project-registry: the loader printed the report before the context existed
            new ConfigurationViolationsException([
                'factory.x is not a known key'
            ])
        ]
    }

    def "FR16: leaves an unclassified fault to Spring Boot's own record"() {
        expect:
        !reporter.reportException(new IllegalStateException('boom'))
    }
}
