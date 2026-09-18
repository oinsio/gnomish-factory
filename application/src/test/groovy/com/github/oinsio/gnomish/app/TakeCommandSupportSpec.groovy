package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Path
import org.slf4j.Logger
import spock.lang.Specification

/**
 * {@link TakeCommandSupport} (task 5.13): direct unit coverage of the startup sandbox-lifecycle
 * sweep {@link TakeCommandSupport#sweepSandboxLifecycle} and of the pipeline load's invalid arm. Tracker-adapter resolution moved to
 * {@link TrackerResolutionSpec} alongside the class it now covers, {@link TrackerResolution}.
 *
 * <p>Implements FR9, FR17 of add-tracker-port.
 */
class TakeCommandSupportSpec extends Specification {

    // FR6, NFR-O4 of add-serve-sandbox-lifecycle: the summary is logged (never carried into the
    // task's finish report), a blank summary says nothing at all, and a failing pass is swallowed
    // — a take that has not even claimed a task must not fail over project-wide hygiene.
    def "sweepSandboxLifecycle logs a non-blank summary, stays silent on a blank one, and swallows a failure"() {
        given:
        def logged = []
        def log = Mock(Logger) {
            info('gnomish take: {}', _) >> { String format, Object arg ->
                logged << arg
            }
        }
        def dir = Path.of('/projects/widgets')
        def liveness = new LivenessVerdict.NoVerdict()

        when: 'a pass reporting work done'
        TakeCommandSupport.sweepSandboxLifecycle({ d, l ->
            'sweep: 1 stopped-orphan'
        } as SandboxLifecyclePass, dir, liveness, log)

        then:
        logged == ['sweep: 1 stopped-orphan']

        when: 'a pass with nothing to report'
        TakeCommandSupport.sweepSandboxLifecycle({ d, l ->
            ''
        } as SandboxLifecyclePass, dir, liveness, log)

        then: 'no second line'
        logged == ['sweep: 1 stopped-orphan']

        when: 'a pass that fails outright'
        TakeCommandSupport.sweepSandboxLifecycle({ d, l ->
            throw new IllegalStateException('docker daemon is unreachable')
        } as SandboxLifecyclePass, dir, liveness, log)

        then:
        noExceptionThrown()
    }

    def "sweepSandboxLifecycle hands the pass this invocation's own directory and liveness verdict"() {
        given:
        def seen = []
        def liveness = new LivenessVerdict.Live(['k1'] as Set)

        when:
        TakeCommandSupport.sweepSandboxLifecycle({ d, l ->
            seen << [d, l]; ''
        } as SandboxLifecyclePass, Path.of('/projects/widgets'), liveness, Mock(Logger))

        then:
        seen == [
            [
                Path.of('/projects/widgets'),
                liveness
            ]
        ]
    }

    // FR12 of add-manual-run; FR5, design D10 of type-untrusted-text: a manifest is a target-repo
    //     file, so a loader error quotes text the factory did not write. The console is this
    //     caller's reader — the exception's lines are printed for an operator — so each error
    //     leaves its carrier through the console exit, which keeps the line structure and the
    //     length a manifest diagnostic needs while showing a hostile control character rather than
    //     acting on it.
    def "loadPipeline renders every invalid-manifest error through the console exit"() {
        given: 'a loader that reports two problems, one of them quoting a hostile manifest value'
        def hostile = 'stage \u001B[2Kname'
        def errors = [
            new ConfigError('.gnomish/pipeline.yaml', 'stages[0].name', hostile),
            new ConfigError('.gnomish/pipeline.yaml', 'stages[1]', 'missing name')
        ]

        when:
        TakeCommandSupport.loadPipeline(
                Path.of('/projects/widgets'), { dir ->
                    new LoadOutcome.Invalid(errors)
                } as PipelineSource)

        then:
        def failure = thrown(PipelineLoadFailedException)
        failure.renderedErrors() == errors.collect { it.render().forConsole() }

        and: 'the escape reached the operator as visible notation, never as a live control sequence'
        failure.renderedErrors()[0].contains('^[')
        !failure.renderedErrors()[0].contains('\u001B')
    }
}
