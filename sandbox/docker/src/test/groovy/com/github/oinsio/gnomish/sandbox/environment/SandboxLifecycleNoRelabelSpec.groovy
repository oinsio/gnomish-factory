package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.git.ProjectScope
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory

/**
 * FR3 of normalize-project-identity-url (design D4): the legacy identity widens only what a pass
 * reads. A pass that acts on a legacy-labelled object stops or removes it under its own name and
 * issues nothing that writes a label — Docker can set labels only at creation, and the pass
 * creates nothing — so no existing object is relabelled. The write side, which stamps the
 * normalized identity alone, is {@code ProjectIdentityStampSpec} in {@code application}.
 */
class SandboxLifecycleNoRelabelSpec extends SandboxLifecycleSweepSpecBase {

    static final def SCOPE = new ProjectScope('norm-1', Optional.of('legacy-1'))

    // FR3: acting on a legacy-labelled box stops it and writes no label on it or on anything else
    // Scenario: sandbox/sandbox-lifecycle / Objects are stamped with the normalized identity and never relabelled
    def "FR3: a pass that stops a legacy-labelled box creates, updates and relabels nothing"() {
        given: 'an unowned running main box that carries only the legacy project label'
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return gone()
            }
            if (args == DockerLifecycleCommands.listFactoryContainersWithLabels('legacy-1')) {
                return ok("gnomish-box-old\tcom.github.oinsio.gnomish.task=old,com.github.oinsio.gnomish.mode=tracked\n")
            }
            if (args == DockerLifecycleCommands.inspectContainerTiming('gnomish-box-old')) {
                return ok("true 0001-01-01T00:00:00Z ${OLD} ${OLD}")
            }
            ok('')
        }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live([] as Set), NOW, THRESHOLDS)

        then: 'the pass acted on it'
        verdicts*.category() == [
            SweepVerdictCategory.STOPPED_ORPHAN
        ]
        docker.runs.contains(DockerCommands.stop('gnomish-box-old'))

        and: 'nothing it ran creates an object or writes a label'
        def writes = [
            'run',
            'create',
            'update',
            'label',
            'container update',
            'container create',
            'volume create',
            'network create'
        ]
        docker.runs.every { List<String> argv ->
            !(argv.first() in writes) && !(argv.take(2).join(' ') in writes) &&
            !argv.any { it == '--label' || it == '--label-add' }
        }
    }
}
