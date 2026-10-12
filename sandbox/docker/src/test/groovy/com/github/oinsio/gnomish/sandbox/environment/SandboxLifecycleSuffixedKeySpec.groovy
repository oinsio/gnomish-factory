package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.git.ProjectScope
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory

/**
 * FR4 of add-serve-sandbox-lifecycle: a fresh judge box ({@code <key>-j}) and a fresh verification
 * box ({@code <key>-v}) belong to their base task — through the whole sweep (listing, role
 * classification, base-key resolution, decision), a suffixed object of a live task is alive, while
 * the same shape for a task that is not live is removed on sight.
 */
class SandboxLifecycleSuffixedKeySpec extends SandboxLifecycleSweepSpecBase {

    static final String PROJECT = 'proj-1'
    static final def SCOPE = new ProjectScope(PROJECT, Optional.empty())

    private static String labels(String key) {
        "com.github.oinsio.gnomish.task=${key},com.github.oinsio.gnomish.mode=tracked"
    }

    // Scenario: sandbox/execution-environment / Judge and verification objects of a live task classify alive
    def "FR4: a -j and a -v object of a live task resolve to the base key and classify alive"() {
        given: 'a judge and a verification box of live task t1, and a judge box of task t2, which is not live'
        def boxes = ['t1-j', 't1-v', 't2-j']
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return gone()
            }
            if (args == DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)) {
                return ok(boxes.collect {
                    "gnomish-box-${it}\t${labels(it)}\n"
                }.join(''))
            }
            if (boxes.any {
                        args == DockerLifecycleCommands.inspectContainerTiming("gnomish-box-${it}")
                    }) {
                return ok("true 0001-01-01T00:00:00Z ${OLD} ${OLD}")
            }
            ok('')
        }

        when:
        sweep.evaluate(SCOPE, new LivenessVerdict.Live(['t1'] as Set), NOW, THRESHOLDS)

        then: 'both suffixed objects of t1 are alive, each under its own role'
        def byName = verdicts.collectEntries { [(it.objectName()): it] }
        byName['gnomish-box-t1-j'].category() == SweepVerdictCategory.CHECKED_ALIVE
        byName['gnomish-box-t1-v'].category() == SweepVerdictCategory.CHECKED_ALIVE
        byName['gnomish-box-t1-j'].role() != byName['gnomish-box-t1-v'].role()

        and: 'the judge box of the task that is not live is removed on sight; nothing of t1 is touched'
        byName['gnomish-box-t2-j'].category() != SweepVerdictCategory.CHECKED_ALIVE
        1 * disposal.dispose('t2-j')
        0 * disposal.dispose({ it.startsWith('t1') })
    }
}
