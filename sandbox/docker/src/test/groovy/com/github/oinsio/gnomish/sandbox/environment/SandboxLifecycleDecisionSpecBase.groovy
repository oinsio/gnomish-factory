package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * The shared fixture of the `sandbox-lifecycle` decision-matrix specs: one clock instant, one set
 * of thresholds, and the hand-built classification/timing/object constructors every matrix row
 * needs. The matrix itself is split by the axis each spec drives — tracked ownership, manual
 * ownership, container-less remnants — so no single file carries the whole table (file-size
 * target, `process-invariants.md`).
 *
 * <p>FR4, FR5, FR7, FR9, NFR-S2, M4 of add-serve-sandbox-lifecycle.
 */
abstract class SandboxLifecycleDecisionSpecBase extends Specification {

    static final Instant NOW = Instant.parse('2026-08-07T12:00:00Z')
    static final Duration MIN_AGE = Duration.ofMinutes(5)
    static final Duration REAP = Duration.ofDays(7)
    static final Duration MANUAL_THRESHOLD = Duration.ofHours(24)
    static final def THRESHOLDS = new SandboxLifecycleThresholds(MIN_AGE, REAP, MANUAL_THRESHOLD)
    static final def OLD = NOW - Duration.ofDays(30)
    static final def LIVE = new LivenessVerdict.Live(['alive'] as Set)
    static final def UNOWNED = new LivenessVerdict.Live([] as Set)
    static final def NO_VERDICT = new LivenessVerdict.NoVerdict()

    def docker = new RecordingDockerCli()
    def disposal = Mock(TaskEnvironmentDisposal)
    def verdicts = []
    SweepVerdictListener listener = { SweepVerdict v -> verdicts << v }
    def decision = new SandboxLifecycleDecision(docker, disposal, listener)

    def setup() {
        // The key-triple dispose path reads its outcome back with an existence probe; the Mock
        // disposal removes nothing, so the probe is scripted as "gone" for the happy-path features.
        docker.onRun = { List<String> args ->
            existenceProbe(args) ? new DockerResult(1, '', 'No such object') : new DockerResult(0, '', '')
        }
    }

    protected static boolean existenceProbe(List<String> args) {
        args.any { it == '{{.Id}}' || it == '{{.Name}}' }
    }

    protected static SandboxLifecycleClassification cls(
            String key, ObjectRole role, OwnershipMode mode, String base = key) {
        new SandboxLifecycleClassification(key, base, role, mode)
    }

    protected static ObjectTiming running(Instant createdAt = OLD, Instant startedAt = OLD) {
        new ObjectTiming(true, createdAt, startedAt, null)
    }

    protected static ObjectTiming stopped(Instant createdAt, Instant finishedAt) {
        new ObjectTiming(false, createdAt, null, finishedAt)
    }

    protected static ListedDockerObject obj(String name = 'n') {
        new ListedDockerObject(name, ObjectKind.CONTAINER, [:])
    }

    protected static ListedDockerObject volume(String name) {
        new ListedDockerObject(name, ObjectKind.VOLUME, [:])
    }

    protected static ListedDockerObject network(String name) {
        new ListedDockerObject(name, ObjectKind.NETWORK, [:])
    }
}
