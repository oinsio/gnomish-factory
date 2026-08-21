package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
import java.nio.file.Path
import spock.lang.Specification

/**
 * {@link SandboxLifecyclePass}'s seam itself: the host-only {@link SandboxLifecyclePass#NONE}
 * no-op, and the sink-taking overload's default (tasks 6.1/6.2 of add-serve-sandbox-lifecycle) —
 * an implementation that has no verdicts to deliver anywhere must still answer with the same
 * summary line its two-argument form returns, or `take`'s finish report would silently blank out
 * whenever the daemon's sinks are attached.
 */
class SandboxLifecyclePassSpec extends Specification {

    static final Path CLONE_DIR = Path.of('/tmp/clone')

    def "NONE evaluates nothing and reports nothing"() {
        expect:
        SandboxLifecyclePass.NONE.run(CLONE_DIR, new LivenessVerdict.NoVerdict()) == ''
        SandboxLifecyclePass.NONE.run(CLONE_DIR, new LivenessVerdict.NoVerdict(), SweepVerdictListener.IGNORE) == ''
    }

    // NFR-O4: the default overload delegates to the two-argument form and returns ITS summary,
    //     so an implementation that ignores the extra sink still reports what it swept.
    def "the default sink-taking overload returns the two-argument form's own summary"() {
        given:
        def seen = []
        SandboxLifecyclePass pass = { Path dir, LivenessVerdict liveness ->
            seen << dir
            'sweep: 2 checked-alive'
        }

        when:
        def summary = pass.run(CLONE_DIR, new LivenessVerdict.NoVerdict(), SweepVerdictListener.IGNORE)

        then:
        summary == 'sweep: 2 checked-alive'
        seen == [CLONE_DIR]
    }
}
