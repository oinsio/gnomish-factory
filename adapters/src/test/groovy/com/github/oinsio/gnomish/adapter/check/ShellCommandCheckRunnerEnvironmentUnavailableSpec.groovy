package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, NFR-R1 of add-sandbox-core: an unavailable check environment (a failed fresh-box
 * materialization surfacing as {@link CheckEnvironmentUnavailableException}) classifies as
 * {@link Verdict.CannotVerify} — an infrastructure failure, no stage attempt burned — and the
 * verdict's reason carries the exception's message verbatim, so the escalation report names the
 * actual cause rather than a wrapped or generic string.
 */
class ShellCommandCheckRunnerEnvironmentUnavailableSpec extends Specification {

    @TempDir
    Path tempDir

    // NFR-R1: the exception's own message becomes the CannotVerify reason verbatim.
    def "an unavailable check environment maps to CannotVerify carrying the exception message verbatim"() {
        given: 'an environment source that refuses with a distinctive message'
        def source = { check, workspace ->
            throw new CheckEnvironmentUnavailableException('fresh-box environment could not be materialized: boom')
        } as CheckEnvironmentSource
        def runner = new ShellCommandCheckRunner().withEnvironments(source)

        when:
        def verdict = runner.run(new VerifyCheck.Command('true'), new DirectoryWorkspace(tempDir))

        then:
        verdict instanceof Verdict.CannotVerify
        (verdict as Verdict.CannotVerify).reason() == 'fresh-box environment could not be materialized: boom'
    }
}
