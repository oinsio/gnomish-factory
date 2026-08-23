package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * The shared fixture of the {@link SandboxLifecycleSweep} specs: one clock instant, one set of
 * thresholds, the recording/mock collaborators, and the docker-result builders every scenario
 * scripts against. Split out so {@code SandboxLifecycleSweepSpec} (single-identity scope) and
 * {@code SandboxLifecycleLegacyScopeSpec} (scope widening) share one definition instead of two
 * drifting copies.
 */
abstract class SandboxLifecycleSweepSpecBase extends Specification {

    static final Instant NOW = Instant.parse('2026-08-07T12:00:00Z')
    static final Instant OLD = NOW - Duration.ofDays(30)
    static final def THRESHOLDS = new SandboxLifecycleThresholds(
    Duration.ofMinutes(5), Duration.ofDays(7), Duration.ofHours(24))

    def docker = new RecordingDockerCli()
    def disposal = Mock(TaskEnvironmentDisposal)
    def verdicts = []
    SweepVerdictListener listener = { SweepVerdict v -> verdicts << v }
    def sweep = new SandboxLifecycleSweep(docker, disposal, listener)

    protected static DockerResult ok(String stdout) {
        new DockerResult(0, stdout, '')
    }

    protected static DockerResult gone() {
        new DockerResult(1, '', 'Error: No such object')
    }

    /** The key-triple dispose reads its outcome back with an existence probe: non-zero means gone. */
    protected static boolean existenceProbe(List<String> args) {
        args.any { it == '{{.Id}}' || it == '{{.Name}}' }
    }
}
