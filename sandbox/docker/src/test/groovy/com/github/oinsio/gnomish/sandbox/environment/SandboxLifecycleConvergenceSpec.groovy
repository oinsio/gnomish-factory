package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * NFR-R2 of add-serve-sandbox-lifecycle (task 3.3): every sweep action is idempotent and
 * crash-safe — re-running the sweep after a crash mid-action converges to the same end state, and
 * partial materialize/dispose residue is reclaimed by the same policy with no special casing.
 *
 * <p>Unlike {@code SandboxLifecycleSweepSpec}, which scripts each command's answer from a fixed
 * closure, this spec drives {@link SandboxLifecycleSweep} against {@link FakeDockerHost} — a host
 * whose object set actually changes when the sweep stops or removes something. A second
 * {@code evaluate} therefore sees the world the first one left behind, which is the only way to
 * ask the convergence question at all.
 *
 * <p>FR4, NFR-R2 of add-serve-sandbox-lifecycle.
 */
class SandboxLifecycleConvergenceSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-08-07T12:00:00Z')
    static final Instant OLD = NOW - Duration.ofDays(30)
    static final def THRESHOLDS = new SandboxLifecycleThresholds(
    Duration.ofMinutes(5), Duration.ofDays(7), Duration.ofHours(24))
    static final def UNOWNED = new LivenessVerdict.Live([] as Set)

    def host = new FakeDockerHost()
    def docker = new RecordingDockerCli(onRun: host.commands())
    def verdicts = []
    SweepVerdictListener listener = { SweepVerdict v -> verdicts << v }
    def sweep = new SandboxLifecycleSweep(docker, host.disposal(), listener)

    private static Map<String, String> labels(String key, String mode = 'tracked') {
        [
            (FactoryDockerLabels.FACTORY_LABEL): 'true',
            (FactoryDockerLabels.TASK_LABEL): key,
            (FactoryDockerLabels.MODE_LABEL): mode,
            (FactoryDockerLabels.PROJECT_LABEL): FakeDockerHost.PROJECT
        ]
    }

    private void evaluate() {
        sweep.evaluate(FakeDockerHost.PROJECT, UNOWNED, NOW, THRESHOLDS)
    }

    // NFR-R2: the first pass stops the unowned running box; the second pass sees a stopped box
    // still under the reap age and keeps it. The end state is identical, and — the point of
    // idempotence — the second pass issues no destructive command at all.
    def "a second pass over the state the first one left issues no further action"() {
        given: 'an unowned running main box with its own volume and network'
        host.add('gnomish-box-k1', ObjectKind.CONTAINER, labels('k1'), OLD, true)
        host.add('gnomish-vol-k1', ObjectKind.VOLUME, labels('k1'), OLD)
        host.add('gnomish-net-k1', ObjectKind.NETWORK, labels('k1'), OLD)

        when: 'the first pass runs'
        evaluate()

        then: 'it stops the box exactly once, disposing nothing'
        verdicts*.category() == [
            SweepVerdictCategory.STOPPED_ORPHAN
        ]
        host.mutations == [
            DockerCommands.stop('gnomish-box-k1')
        ]

        when: 'the sweep runs again over the state the first pass left behind'
        verdicts.clear()
        def mutationsBefore = List.copyOf(host.mutations)
        evaluate()

        then: 'the box is now kept under the reap threshold — and nothing further was touched'
        verdicts*.category() == [
            SweepVerdictCategory.KEPT_UNDER_THRESHOLD
        ]
        host.mutations == mutationsBefore
        host.objects.keySet() == [
            'gnomish-box-k1',
            'gnomish-vol-k1',
            'gnomish-net-k1'
        ] as Set
    }

    // NFR-R2: a crash between the container removal and the volume/network removal leaves exactly
    // the residue this fixture starts from. No special "resume a half-finished dispose" path
    // exists — the remnant rules reclaim it as ordinary container-less objects.
    def "residue from a dispose that crashed halfway is reclaimed by the next pass with no special casing"() {
        given: 'a dispose that removed the container and died before its volume and network'
        host.add('gnomish-vol-k1', ObjectKind.VOLUME, labels('k1'), OLD)
        host.add('gnomish-net-k1', ObjectKind.NETWORK, labels('k1'), OLD)

        when:
        evaluate()

        then: 'one key-triple disposal reclaims both — the network rides along with its volume'
        verdicts*.category() == [
            SweepVerdictCategory.DISPOSED_AGED
        ]
        verdicts[0].taskKey() == 'k1'
        host.objects.isEmpty()

        when: 'the sweep runs once more against the now-empty host'
        verdicts.clear()
        def mutationsBefore = List.copyOf(host.mutations)
        evaluate()

        then: 'there is nothing left to judge and nothing left to remove'
        verdicts.isEmpty()
        host.mutations == mutationsBefore
    }

    // NFR-R2 for the aged-reap escalation: running → stopped → disposed is one-way, and each pass
    // advances it by at most one step. Re-running the second pass converges on "gone" rather than
    // re-issuing the removal.
    def "the reap escalation advances one step per pass and then converges on gone"() {
        given: 'an unowned box already stopped well past the reap age'
        host.add('gnomish-box-k1', ObjectKind.CONTAINER, labels('k1'), OLD, false, OLD)
        host.add('gnomish-vol-k1', ObjectKind.VOLUME, labels('k1'), OLD)

        when: 'the first pass disposes the whole key triple'
        evaluate()

        then:
        verdicts*.category() == [
            SweepVerdictCategory.DISPOSED_AGED
        ]
        host.objects.isEmpty()

        when: 'a third pass runs against what is left'
        verdicts.clear()
        def mutationsBefore = List.copyOf(host.mutations)
        evaluate()

        then: 'no verdict, no command — the end state is stable'
        verdicts.isEmpty()
        host.mutations == mutationsBefore
    }

    // NFR-R2 across modes: a manual box past its running-stop threshold follows the same
    // stop-then-keep escalation, and a repeat pass neither re-stops it nor jumps to disposal.
    def "a manual box past its running threshold converges after one stop, exactly as a tracked one does"() {
        given:
        host.add('gnomish-box-m1', ObjectKind.CONTAINER, labels('m1', 'manual'), OLD, true)

        when:
        evaluate()

        then:
        verdicts*.category() == [
            SweepVerdictCategory.STOPPED_ORPHAN
        ]
        verdicts[0].mode() == 'manual'
        host.mutations == [
            DockerCommands.stop('gnomish-box-m1')
        ]

        when:
        verdicts.clear()
        def mutationsBefore = List.copyOf(host.mutations)
        evaluate()

        then:
        verdicts*.category() == [
            SweepVerdictCategory.KEPT_UNDER_THRESHOLD
        ]
        host.mutations == mutationsBefore
    }
}
