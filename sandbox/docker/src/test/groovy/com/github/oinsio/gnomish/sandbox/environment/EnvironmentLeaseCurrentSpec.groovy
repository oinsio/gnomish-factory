package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import spock.lang.Specification

/**
 * FR12 of add-sandbox-core: {@code EnvironmentLease#current()} hands back the
 * leased environment itself once a stage has run — the strict counterpart of
 * the refuse-before-first-lease scenario in {@code EnvironmentLeaseSpec}.
 *
 * <p>New spec file for task 6.1 of split-into-modules: the happy path of
 * {@code current()} was killed only by specs now in other modules; per-module
 * PIT (D6) needs the kill here — same reasoning as {@code ExecCommandSpec} at
 * task 3.1.
 */
class EnvironmentLeaseCurrentSpec extends Specification {

    private static StageDefinition stage(String name) {
        new StageDefinition(
                name, 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
                'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    // FR12: mid-run collaborators see the leased environment itself, never a fabricated one
    def "current() returns the leased environment once a stage has run"() {
        given: 'a lease whose one segment has materialized an environment'
        def env = Stub(TaskExecutionEnvironment)
        def lease = new EnvironmentLease({ env }, 'gnomish/t', [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage('a')])
        ])
        lease.environmentFor('a')

        expect:
        lease.current().is(env)
    }
}
