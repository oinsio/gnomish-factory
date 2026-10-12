package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.git.ProjectScope
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SkippedVerdictSource
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory

/**
 * `tracker-take` "Startup sweep pass with a reported summary": a tracker read that fails during
 * the pass costs the objects it was needed for their verdict, and nothing else. A take's startup
 * pass reads tracker liveness once, through the claims listing behind the liveness oracle; when
 * that read fails the pass is handed {@link LivenessVerdict.NoVerdict} (the take side of the
 * hand-off is pinned by {@code TakeCommandSweepSpec}). This spec drives the real sweep over that
 * verdict: the tracked object is skipped with no verdict, and the pass still reaches and acts on
 * an object whose decision needs no tracker read, then returns normally so the take proceeds.
 *
 * <p>FR6, NFR-R1, NFR-O4 of add-serve-sandbox-lifecycle.
 */
class SandboxLifecycleNoVerdictPassSpec extends SandboxLifecycleSweepSpecBase {

    static final String PROJECT = 'proj-1'
    static final def SCOPE = new ProjectScope(PROJECT, Optional.empty())

    // Scenario: tracker/tracker-take / A tracker error during the sweep skips the verdict
    def "a failed tracker read skips the tracked object with no verdict while the pass continues"() {
        given: 'one tracked box, whose verdict needs the tracker, and one manual box running past its age'
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return ok('present')
            }
            if (args == DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)) {
                return ok('gnomish-box-t1\tcom.github.oinsio.gnomish.task=t1,com.github.oinsio.gnomish.mode=tracked\n'
                        + 'gnomish-box-m1\tcom.github.oinsio.gnomish.task=m1,com.github.oinsio.gnomish.mode=manual\n')
            }
            if (args == DockerLifecycleCommands.inspectContainerTiming('gnomish-box-t1')
                    || args == DockerLifecycleCommands.inspectContainerTiming('gnomish-box-m1')) {
                return ok("true 0001-01-01T00:00:00Z ${OLD} ${OLD}")
            }
            ok('')
        }

        when: 'the pass runs on the verdict a failed tracker read leaves it'
        sweep.evaluate(SCOPE, new LivenessVerdict.NoVerdict(), NOW, THRESHOLDS)

        then: 'the pass returned normally, so the take that started it proceeds'
        noExceptionThrown()

        and: 'the tracked box is skipped with no verdict, and nothing is done to it'
        def tracked = verdicts.find { it.objectName() == 'gnomish-box-t1' }
        tracked.category() == SweepVerdictCategory.SKIPPED_NO_VERDICT
        tracked.skipSource() == SkippedVerdictSource.TRACKER
        !docker.runs.contains(DockerCommands.stop('gnomish-box-t1'))

        and: 'the pass continued past it and acted on the object that needed no tracker read'
        def manual = verdicts.find { it.objectName() == 'gnomish-box-m1' }
        manual.category() == SweepVerdictCategory.STOPPED_ORPHAN
        docker.runs.contains(DockerCommands.stop('gnomish-box-m1'))
        0 * disposal.dispose(_)
    }
}
