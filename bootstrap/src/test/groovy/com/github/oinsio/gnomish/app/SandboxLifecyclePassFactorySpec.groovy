package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Path
import java.time.Clock
import spock.lang.Specification
import spock.lang.TempDir

/**
 * `SandboxLifecyclePassFactory`, task 4.x of add-serve-sandbox-lifecycle: {@link
 * SandboxLifecyclePass#NONE} on a host-only install (no {@code factory.sandbox.image}); a real,
 * usable pass otherwise.
 */
class SandboxLifecyclePassFactorySpec extends Specification {

    @TempDir
    Path tempDir

    def clock = Clock.systemUTC()

    def "returns SandboxLifecyclePass.NONE when no sandbox image is configured"() {
        given:
        def sandbox = new SandboxProperties(null, null, null, null, [], [], false, null, null, null, null)

        expect:
        SandboxLifecyclePassFactory.create(sandbox, new FactoryProperties(null, null, null, null, null), clock) == SandboxLifecyclePass.NONE
    }

    def "returns a real, usable pass when a sandbox image is configured"() {
        given: 'a project id unique to this run'
        // The pass shells out to the real docker binary, so its listing must match nothing the
        // developer owns. An explicit per-run id states that intent at the seam rather than
        // resting on ProjectIdentity's origin-less fallback (a digest of this run's temp clone
        // path) happening to be unique too.
        def projectId = "spec-${UUID.randomUUID()}".toString()
        def sandbox = new SandboxProperties(
                'gnomish/img', null, null, null, [], [], false, projectId, null, null, null)

        when:
        def pass = SandboxLifecyclePassFactory.create(sandbox, new FactoryProperties(null, null, null, null, null), clock)

        then:
        pass != SandboxLifecyclePass.NONE

        and: 'invoking it against a project-less directory evaluates and returns a summary line, never throws'
        // The exact line, not merely non-null: this project id owns no Docker object, so the tally
        // is empty whether or not a daemon is reachable — a deterministic assertion on both
        // overloads' return value, and the one that keeps them inside the mutation gate now that
        // only the sweep hand-off itself is @DoNotMutate.
        pass.run(tempDir, new LivenessVerdict.NoVerdict()) == 'sweep: nothing to report'

        and: 'the three-argument overload, with an extra verdict sink, reports the same'
        pass.run(tempDir, new LivenessVerdict.NoVerdict(), SweepVerdictListener.IGNORE) == 'sweep: nothing to report'
    }
}
