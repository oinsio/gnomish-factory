package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.git.ProjectScope
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory

/**
 * `sandbox-lifecycle` end to end through {@link SandboxLifecycleSweep}: project-scoped listing,
 * the container-less-remnant correlation (a main box's volume/network ride along with its own
 * container's verdict), and the whole-pass outage skip.
 *
 * FR4, FR8, NFR-R3 of add-serve-sandbox-lifecycle.
 */
class SandboxLifecycleSweepSpec extends SandboxLifecycleSweepSpecBase {

    static final String PROJECT = 'proj-1'
    static final def SCOPE = new ProjectScope(PROJECT, Optional.empty())

    def "create builds a real, usable sweep over the real docker binary"() {
        expect:
        SandboxLifecycleSweep.create(listener) != null
    }

    def "lists only this project's objects, using both the factory and project label filters"() {
        given:
        docker.onRun = { List<String> args -> ok('') }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live([] as Set), NOW, THRESHOLDS)

        then:
        docker.runs.contains(DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT))
        docker.runs.contains(DockerLifecycleCommands.listFactoryVolumesWithLabels(PROJECT))
        docker.runs.contains(DockerLifecycleCommands.listFactoryNetworksWithLabels(PROJECT))
    }

    def "a main box's volume and network ride along with its container's verdict, untouched independently"() {
        given: 'an alive main box container plus its own volume and network'
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return gone()
            }
            if (args == DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)) {
                return ok("gnomish-box-alive\tcom.github.oinsio.gnomish.task=alive,com.github.oinsio.gnomish.mode=tracked\n")
            }
            if (args == DockerLifecycleCommands.listFactoryVolumesWithLabels(PROJECT)) {
                return ok("gnomish-vol-alive\tcom.github.oinsio.gnomish.task=alive,com.github.oinsio.gnomish.mode=tracked\n")
            }
            if (args == DockerLifecycleCommands.listFactoryNetworksWithLabels(PROJECT)) {
                return ok('')
            }
            if (args == DockerLifecycleCommands.inspectContainerTiming('gnomish-box-alive')) {
                return ok("true 0001-01-01T00:00:00Z ${OLD} ${OLD}")
            }
            ok('')
        }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live(['alive'] as Set), NOW, THRESHOLDS)

        then: 'only the container is evaluated — its volume is never independently inspected or touched'
        verdicts.size() == 1
        verdicts[0].category() == SweepVerdictCategory.CHECKED_ALIVE
        !docker.runs.contains(DockerLifecycleCommands.inspectVolumeCreatedAt('gnomish-vol-alive'))
    }

    def "a container-less volume remnant is independently evaluated and aged-reaped"() {
        given:
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return gone()
            }
            if (args == DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)) {
                return ok('')
            }
            if (args == DockerLifecycleCommands.listFactoryVolumesWithLabels(PROJECT)) {
                return ok("gnomish-vol-orphan\tcom.github.oinsio.gnomish.task=orphan,com.github.oinsio.gnomish.mode=tracked\n")
            }
            if (args == DockerLifecycleCommands.listFactoryNetworksWithLabels(PROJECT)) {
                return ok('')
            }
            if (args == DockerLifecycleCommands.inspectVolumeCreatedAt('gnomish-vol-orphan')) {
                return ok(OLD.toString())
            }
            ok('')
        }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live([] as Set), NOW, THRESHOLDS)

        then:
        verdicts.size() == 1
        verdicts[0].category() == SweepVerdictCategory.DISPOSED_AGED
        1 * disposal.dispose('orphan')
    }

    def "a container-less network remnant is independently evaluated and aged-reaped"() {
        given:
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return gone()
            }
            if (args == DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)) {
                return ok('')
            }
            if (args == DockerLifecycleCommands.listFactoryVolumesWithLabels(PROJECT)) {
                return ok('')
            }
            if (args == DockerLifecycleCommands.listFactoryNetworksWithLabels(PROJECT)) {
                return ok("gnomish-net-orphan\tcom.github.oinsio.gnomish.task=orphan,com.github.oinsio.gnomish.mode=tracked\n")
            }
            if (args == DockerLifecycleCommands.inspectNetworkCreatedAt('gnomish-net-orphan')) {
                return ok(OLD.toString())
            }
            ok('')
        }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live([] as Set), NOW, THRESHOLDS)

        then:
        verdicts.size() == 1
        verdicts[0].category() == SweepVerdictCategory.DISPOSED_AGED
        1 * disposal.dispose('orphan')
    }

    def "a same-keyed guard container never suppresses independent evaluation of the main box's own volume"() {
        given: 'no real main-box container exists for k1 — only its guard, which shares the key'
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return gone()
            }
            if (args == DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)) {
                return ok("gnomish-guard-k1\tcom.github.oinsio.gnomish.task=k1,com.github.oinsio.gnomish.mode=tracked\n")
            }
            if (args == DockerLifecycleCommands.listFactoryVolumesWithLabels(PROJECT)) {
                return ok("gnomish-vol-k1\tcom.github.oinsio.gnomish.task=k1,com.github.oinsio.gnomish.mode=tracked\n")
            }
            if (args == DockerLifecycleCommands.listFactoryNetworksWithLabels(PROJECT)) {
                return ok('')
            }
            if (args == DockerLifecycleCommands.inspectContainerTiming('gnomish-guard-k1')) {
                return ok("true 0001-01-01T00:00:00Z ${OLD} ${OLD}")
            }
            if (args == DockerLifecycleCommands.inspectVolumeCreatedAt('gnomish-vol-k1')) {
                return ok(OLD.toString())
            }
            ok('')
        }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live([] as Set), NOW, THRESHOLDS)

        then: 'both the guard and the main-box volume are independently evaluated and emit a verdict'
        verdicts.size() == 2
        verdicts*.category().containsAll([
            SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE,
            SweepVerdictCategory.DISPOSED_AGED
        ])
    }

    // NFR-R1: a container listing that exits non-zero without the daemon reporting itself
    // unreachable must not read as "no containers" — every main box would look container-less and
    // its still-mounted volume would be disposed out from under a live session.
    def "a failed container listing aborts the whole pass instead of orphaning every live main box"() {
        given: 'the container listing fails, while the volume of a live manual box lists fine'
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return gone()
            }
            if (args == DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)) {
                return new DockerResult(1, '', 'Error response from daemon: filter failed')
            }
            if (args == DockerLifecycleCommands.listFactoryVolumesWithLabels(PROJECT)) {
                return ok("gnomish-vol-k1\tcom.github.oinsio.gnomish.task=k1,com.github.oinsio.gnomish.mode=manual\n")
            }
            if (args == DockerLifecycleCommands.inspectVolumeCreatedAt('gnomish-vol-k1')) {
                return ok(OLD.toString())
            }
            ok('')
        }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live([] as Set), NOW, THRESHOLDS)

        then: 'nothing is judged and nothing is removed; the pass is retried on the next tick'
        thrown(DockerUnavailableException)
        verdicts.isEmpty()
        0 * disposal.dispose(_)
    }

    // NFR-O3: the outage reaches the caller instead of being swallowed here, so an aborted pass
    // completes no tick — a permanently unreachable daemon must not publish an empty tally as a
    // healthy zero-work tick, which would reset the consecutive-skipped run length and leave the
    // stalled sweep invisible to every alert.
    def "a docker runtime outage aborts the whole pass and reaches the caller"() {
        given:
        docker.onRun = { List<String> args ->
            throw new DockerUnavailableException('down', null)
        } as Closure<DockerResult>

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live([] as Set), NOW, THRESHOLDS)

        then:
        thrown(DockerUnavailableException)
        verdicts.isEmpty()
    }
}
