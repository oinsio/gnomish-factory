package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import java.util.function.Function
import java.util.function.Supplier
import spock.lang.Specification

/**
 * FR3, FR11, NFR-S1 of migrate-verify-to-executor-contract (design D6, D11 as amended 2026-11-14):
 * the sandboxed check source as a {@code program} verdict check's {@link
 * com.github.oinsio.gnomish.app.port.check.FreshBoxSource} — each request's box built for the
 * executor's own grant, materialized at the commit its binding names, disposed on release, and
 * disposed before a failed materialization surfaces as unavailable. The command check's boxes are
 * {@link SandboxCheckEnvironmentSourceSpec}'s.
 */
class SandboxProgramBoxSourceSpec extends Specification {

    private static final String SHA = 'a'.repeat(40)

    private static final EnvironmentLease NO_LEASE = new EnvironmentLease({
        ->
        throw new IllegalStateException('no round box expected')
    }, 'gnomish/t', [])

    private static final Supplier<TaskExecutionEnvironment> NO_FRESH_BOX = {
        ->
        throw new IllegalStateException('no command-check box expected')
    } as Supplier<TaskExecutionEnvironment>

    def "FR11, D6: a program check's box is built for the executor's grant, materialized at the named commit and disposed on close"() {
        given:
        def fresh = Mock(TaskExecutionEnvironment)
        def grants = []
        def source = new SandboxCheckEnvironmentSource(NO_LEASE, NO_FRESH_BOX,
                ({ List<String> grant ->
                    grants << grant
                    fresh
                } as Function<List<String>, TaskExecutionEnvironment>), 'gnomish/t')

        when:
        def acquired = source.freshBox(SHA, ['api.acme.example'])

        then:
        1 * fresh.materialize('gnomish/t', SHA)
        grants == [['api.acme.example']]
        acquired.environment().is(fresh)
        0 * fresh.dispose()

        when:
        acquired.close()

        then:
        1 * fresh.dispose()
    }

    def "NFR-R1: a program check's box that fails to materialize is disposed and surfaces as unavailable"() {
        given:
        def fresh = Mock(TaskExecutionEnvironment)
        def source = new SandboxCheckEnvironmentSource(NO_LEASE, NO_FRESH_BOX,
                ({ List<String> grant ->
                    fresh
                } as Function<List<String>, TaskExecutionEnvironment>), 'gnomish/t')

        when:
        source.freshBox(SHA, [])

        then:
        1 * fresh.materialize('gnomish/t', SHA) >> {
            throw new RuntimeException('docker died')
        }
        1 * fresh.dispose()
        thrown(CheckEnvironmentUnavailableException)
    }
}
