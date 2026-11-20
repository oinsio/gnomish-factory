package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, NFR-R1 of add-sandbox-core: an unavailable check environment (a failed fresh-box
 * materialization surfacing as {@link CheckEnvironmentUnavailableException}) is judged
 * {@link ShellOutcome.Unanswerable} — which {@link ShellExecutor} answers {@code failed/infrastructure},
 * no stage attempt burned — and its reason carries the exception's message verbatim, so the escalation report names the
 * actual cause rather than a wrapped or generic string.
 */
class ShellCommandCheckRunnerEnvironmentUnavailableSpec extends Specification implements ShellCommandCheckRunnerTestSupport {

    @TempDir
    Path tempDir

    // NFR-R1: the exception's own message becomes the CannotVerify reason verbatim.
    def "an unavailable check environment maps to CannotVerify carrying the exception message verbatim"() {
        given: 'an environment source that refuses with a distinctive message'
        def source = { check, workspace ->
            throw new CheckEnvironmentUnavailableException('fresh-box environment could not be materialized: boom')
        } as CheckEnvironmentSource
        def runner = new ShellCommandCheckRunner(new VirtualClock()).withEnvironments(source)

        when:
        def outcome = runner.probe(command('true'), workspace())

        then:
        outcome instanceof ShellOutcome.Unanswerable
        (outcome as ShellOutcome.Unanswerable).reason().forLog() == 'fresh-box environment could not be materialized: boom'
    }
}
