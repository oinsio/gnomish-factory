package com.github.oinsio.gnomish.adapter.environment

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import spock.lang.Specification

/**
 * FR12, FR13, NFR-P1 of add-sandbox-core (the integration pass): the
 * environment lease reuses one materialized environment within a segment,
 * executes harvest → dispose → materialize across a boundary, and never
 * materializes before the first stage asks.
 */
class EnvironmentLeaseSpec extends Specification {

    private static StageDefinition stage(String name) {
        new StageDefinition(
                name, 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
                'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    def events = []

    private TaskExecutionEnvironment fake(int id) {
        Stub(TaskExecutionEnvironment) {
            materialize(_, _) >> { b, pin -> events << "materialize-${id}:${b}" }
            harvest() >> { events << "harvest-${id}" }
            dispose() >> { events << "dispose-${id}" }
        }
    }

    def "FR12: stages of one segment reuse the same environment with no re-materialization"() {
        given:
        def env = fake(1)
        def lease = new EnvironmentLease({ env }, 'gnomish/t', [
            new Segment(AdapterBinding.CONTAINER, [stage('a'), stage('b')])
        ])

        when:
        def first = lease.environmentFor('a')
        def second = lease.environmentFor('b')

        then: 'one materialization, same instance both times (NFR-P1)'
        first.is(second)
        events == ['materialize-1:gnomish/t']
    }

    def "FR12/FR13: a segment boundary executes harvest, dispose, materialize in order"() {
        given:
        def envs = [fake(1), fake(2)].iterator()
        def lease = new EnvironmentLease({ envs.next() }, 'gnomish/t', [
            new Segment(AdapterBinding.CONTAINER, [stage('a')]),
            new Segment(AdapterBinding.CONTAINER, [stage('b')]),
        ])

        when:
        lease.environmentFor('a')
        lease.environmentFor('b')

        then:
        events == [
            'materialize-1:gnomish/t',
            'harvest-1',
            'dispose-1',
            'materialize-2:gnomish/t'
        ]
    }

    def "materialization is lazy and current() refuses before the first lease"() {
        given:
        def lease = new EnvironmentLease({ fake(1) }, 'gnomish/t', [
            new Segment(AdapterBinding.CONTAINER, [stage('a')])
        ])

        when:
        lease.current()

        then:
        thrown(IllegalStateException)
        events.isEmpty()
        lease.currentIfLeased().isEmpty()
    }

    // FR12: end-of-run bookkeeping sees the leased environment itself, never a fabricated empty
    def "currentIfLeased exposes the leased environment once a stage has run"() {
        given:
        def env = fake(1)
        def lease = new EnvironmentLease({ env }, 'gnomish/t', [
            new Segment(AdapterBinding.CONTAINER, [stage('a')])
        ])
        lease.environmentFor('a')

        expect:
        lease.currentIfLeased().get().is(env)
    }

    def "a stage outside the plan is refused"() {
        given:
        def lease = new EnvironmentLease({ fake(1) }, 'gnomish/t', [
            new Segment(AdapterBinding.CONTAINER, [stage('a')])
        ])

        when:
        lease.environmentFor('unplanned')

        then:
        thrown(IllegalArgumentException)
    }

    def "dispose is idempotent and clears the lease"() {
        given:
        def env = fake(1)
        def lease = new EnvironmentLease({ env }, 'gnomish/t', [
            new Segment(AdapterBinding.CONTAINER, [stage('a')])
        ])
        lease.environmentFor('a')

        when:
        lease.dispose()
        lease.dispose()

        then:
        events == [
            'materialize-1:gnomish/t',
            'dispose-1'
        ]
        lease.currentIfLeased().isEmpty()
    }
}
