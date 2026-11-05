package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * `SandboxLifecyclePassFactory`, task 4.x of add-serve-sandbox-lifecycle: {@link
 * SandboxLifecyclePass#NONE} on a host-only install (no {@code factory.sandbox.image}); a real,
 * usable pass otherwise. The summary line is asserted only where a daemon is reachable: the pass's
 * listing fails closed on an unreachable one, so no daemon-free answer exists to assert.
 */
class SandboxLifecyclePassFactorySpec extends Specification {

    @TempDir
    Path tempDir

    def clock = new VirtualClock()

    def "returns SandboxLifecyclePass.NONE when no sandbox image is configured"() {
        given:
        def sandbox = new SandboxProperties(null, null, null, null, [], [], false, null, null, null, null)

        expect:
        SandboxLifecyclePassFactory.create(sandbox, new FactoryProperties(null, null, null, null), clock) == SandboxLifecyclePass.NONE
    }

    def "returns a real pass when a sandbox image is configured"() {
        expect: 'no daemon is involved: building the pass runs no docker command'
        SandboxLifecyclePassFactory.create(sandbox(), new FactoryProperties(null, null, null, null), clock) != SandboxLifecyclePass.NONE
    }

    // The pass shells out to the real docker binary, and its listing is fail-closed (NFR-R1 of
    // add-serve-sandbox-lifecycle): an unreachable daemon is "cannot enumerate", thrown, never an
    // empty world read as "nothing to report". So the summary line needs a reachable daemon.
    @IgnoreIf(value = { !dockerCliReachable() },
    reason = 'Docker daemon unavailable — the pass lists real docker objects, and an unreachable daemon fails the listing closed')
    def "a real pass over a project that owns no docker object reports nothing, through both overloads"() {
        given:
        def pass = SandboxLifecyclePassFactory.create(sandbox(), new FactoryProperties(null, null, null, null), clock)

        expect: 'the exact line, which keeps both overloads\' return values inside the mutation gate'
        pass.run(tempDir, new LivenessVerdict.NoVerdict()) == 'sweep: nothing to report'

        and: 'the three-argument overload, with an extra verdict sink, reports the same'
        pass.run(tempDir, new LivenessVerdict.NoVerdict(), SweepVerdictListener.IGNORE) == 'sweep: nothing to report'
    }

    /**
     * A sandbox configuration under a project id unique to this run: the pass's listing must match
     * nothing the developer owns, and an explicit per-run id states that intent at the seam rather
     * than resting on {@code ProjectIdentity}'s origin-less fallback happening to be unique too.
     */
    private static SandboxProperties sandbox() {
        new SandboxProperties('gnomish/img', null, null, null, [], [], false, "spec-${UUID.randomUUID()}".toString(),
        null, null, null)
    }

    /**
     * Whether the {@code docker} CLI — the client the pass itself runs — reaches a daemon. Asked of
     * the CLI rather than of Testcontainers' probe, which falls back to other sockets the CLI does
     * not use, so the two can disagree. Bounded: a hung client reads as unreachable.
     */
    private static boolean dockerCliReachable() {
        try {
            def process = new ProcessBuilder('docker', 'version', '--format', '{{.Server.Version}}')
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            return process.exitValue() == 0
        } catch (IOException ignored) {
            return false
        }
    }
}
