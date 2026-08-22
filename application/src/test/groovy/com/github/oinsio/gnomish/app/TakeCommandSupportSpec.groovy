package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Path
import org.slf4j.Logger
import spock.lang.Specification

/**
 * {@link TakeCommandSupport} (task 5.13): direct unit coverage of {@link
 * TakeCommandSupport#resolveTracker}, the small {@code resolveFactory().create(...)} combinator
 * seam that {@link TakeCommand} itself does not call directly (it inlines the same two steps to
 * keep its own "unknown tracker.type" refusal path readable) but that remains a documented,
 * reusable seam for other callers of {@link TakeCommandSupport}.
 *
 * <p>Implements FR9, FR17 of add-tracker-port.
 */
class TakeCommandSupportSpec extends Specification {

    def "resolveTracker resolves the registered factory and builds a live Tracker from it"() {
        given: 'a registry with one factory registered for the configured type'
        def trackerConfig = new TrackerConfig('fixture', 3, [:])
        def tracker = Mock(Tracker)
        def factory = Mock(TrackerAdapterFactory) {
            create(MapSecretsProvider.NONE, trackerConfig, 'gnomish-factory-a1') >> tracker
        }
        def registry = [fixture: factory]

        when:
        def resolved = TakeCommandSupport.resolveTracker(trackerConfig, registry, MapSecretsProvider.NONE, 'gnomish-factory-a1')

        then:
        resolved == tracker
    }

    def "resolveTracker refuses when no factory is registered for the configured type"() {
        given: 'an empty registry'
        def trackerConfig = new TrackerConfig('unknown-type', 3, [:])

        when:
        TakeCommandSupport.resolveTracker(trackerConfig, [:], MapSecretsProvider.NONE, 'gnomish-factory-a1')

        then:
        def ex = thrown(UsageException)
        ex.message.contains('unknown-type')
    }

    def "the refusal lists the supported tracker types, sorted, so the operator hint is actionable"() {
        given: 'a registry with several known types in non-sorted insertion order'
        def trackerConfig = new TrackerConfig('unknown-type', 3, [:])
        def registry = [inmemory: Mock(TrackerAdapterFactory), github: Mock(TrackerAdapterFactory)]

        when:
        TakeCommandSupport.resolveTracker(trackerConfig, registry, MapSecretsProvider.NONE, 'gnomish-factory-a1')

        then: 'FR17: the message names the registered types as a stable, sorted list'
        def ex = thrown(UsageException)
        ex.message.contains('github, inmemory')
    }

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

    def "supportedTypes renders the registered type keys sorted and comma-joined"() {
        expect:
        TakeCommandSupport.supportedTypes(registry) == expected

        where:
        registry || expected
        [:] || ''
        [github: Mock(TrackerAdapterFactory)] || 'github'
        [inmemory: Mock(TrackerAdapterFactory), github: Mock(TrackerAdapterFactory)] || 'github, inmemory'
    }
}
