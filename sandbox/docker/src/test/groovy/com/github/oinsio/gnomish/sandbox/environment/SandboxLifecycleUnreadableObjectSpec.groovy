package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.git.ProjectScope
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory

/**
 * `sandbox-lifecycle` "Unreadable object still gets a verdict" (FR12, FR5 of
 * harden-logging-observability): an object the pass enumerated but could not inspect used to fall
 * out of the pass silently — never reaped, never reported. It now leaves the pass with the one
 * verdict that says so, so the stall is visible on the operator plane instead of being invisible
 * in both places.
 *
 * <p>The distinction that keeps that from crying wolf is asserted here too: an object that is no
 * longer there — this pass reclaimed it through a sibling's key triple, or another instance
 * removed it between the listing and the inspect — left no work undone and gets no verdict.
 */
class SandboxLifecycleUnreadableObjectSpec extends SandboxLifecycleSweepSpecBase {

    static final String PROJECT = 'proj-1'
    static final def SCOPE = new ProjectScope(PROJECT, Optional.empty())
    static final String LABELS = 'com.github.oinsio.gnomish.task=t1,com.github.oinsio.gnomish.mode=tracked'

    def "an enumerated container whose inspect fails leaves the pass with skipped-no-verdict"() {
        given: 'the container is listed, but its timing inspect no longer resolves'
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return ok('present') // the object really is still there: unreadable, not reclaimed
            }
            if (args == DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)) {
                return ok("gnomish-box-t1\t${LABELS}\n")
            }
            if (args == DockerLifecycleCommands.inspectContainerTiming('gnomish-box-t1')) {
                return gone()
            }
            ok('')
        }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live([] as Set), NOW, THRESHOLDS)

        then: 'the object is accounted for, naming the read failure, and nothing was disposed'
        verdicts.size() == 1
        verdicts[0].category() == SweepVerdictCategory.SKIPPED_NO_VERDICT
        verdicts[0].objectName() == 'gnomish-box-t1'
        verdicts[0].reason().contains('could not be read')

        and: 'no age is claimed for an object whose creation time was never read'
        verdicts[0].age() == null

        and:
        0 * disposal.dispose(_)
    }

    def "an inspect whose output is not the shape the reader parses is a read failure too"() {
        given: 'the timing inspect answers, but with fewer fields than the reader expects'
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return ok('present') // the object really is still there: unreadable, not reclaimed
            }
            if (args == DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)) {
                return ok("gnomish-box-t1\t${LABELS}\n")
            }
            if (args == DockerLifecycleCommands.inspectContainerTiming('gnomish-box-t1')) {
                return ok('true')
            }
            ok('')
        }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live([] as Set), NOW, THRESHOLDS)

        then:
        verdicts.size() == 1
        verdicts[0].category() == SweepVerdictCategory.SKIPPED_NO_VERDICT
    }

    def "an object that vanished between the listing and the inspect is not a degradation"() {
        given: 'the volume is listed, but by inspect time it is gone — and its existence probe agrees'
        docker.onRun = { List<String> args ->
            if (args == DockerLifecycleCommands.listFactoryVolumesWithLabels(PROJECT)) {
                return ok("gnomish-vol-t1\t${LABELS}\n")
            }
            if (args == DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)
                    || args == DockerLifecycleCommands.listFactoryNetworksWithLabels(PROJECT)) {
                return ok('')
            }
            gone()
        }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live([] as Set), NOW, THRESHOLDS)

        then: 'nothing is reported: an object that is gone is not an object left in an unknown state'
        verdicts.isEmpty()
    }

    def "an enumerated remnant whose creation time cannot be read gets the same verdict"() {
        given:
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return ok('present') // the object really is still there: unreadable, not reclaimed
            }
            if (args == DockerLifecycleCommands.listFactoryVolumesWithLabels(PROJECT)) {
                return ok("gnomish-vol-t1\t${LABELS}\n")
            }
            if (args == DockerLifecycleCommands.inspectVolumeCreatedAt('gnomish-vol-t1')) {
                return gone()
            }
            ok('')
        }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live([] as Set), NOW, THRESHOLDS)

        then:
        verdicts.size() == 1
        verdicts[0].category() == SweepVerdictCategory.SKIPPED_NO_VERDICT
        verdicts[0].objectName() == 'gnomish-vol-t1'
    }
}
