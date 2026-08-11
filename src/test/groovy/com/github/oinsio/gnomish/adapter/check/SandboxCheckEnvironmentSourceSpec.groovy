package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.adapter.environment.AdapterBinding
import com.github.oinsio.gnomish.adapter.environment.EnvironmentLease
import com.github.oinsio.gnomish.adapter.environment.Segment
import com.github.oinsio.gnomish.adapter.environment.TaskExecutionEnvironment
import com.github.oinsio.gnomish.adapter.git.AttemptCommitRef
import com.github.oinsio.gnomish.adapter.workspace.AttemptCommitWorkspace
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import java.util.function.Supplier
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of add-sandbox-core (the integration pass): {@code same-box} checks run
 * in the round's leased environment and release nothing (the lease owns it);
 * {@code fresh-box} checks run in a fresh environment materialized from the
 * attempt commit — disposed on release, and disposed on a failed
 * materialization too before the failure surfaces as an infrastructure
 * failure (NFR-R1); {@code fresh-box} refuses fail-closed without an
 * attempt-commit workspace — never a silent same-box downgrade.
 */
class SandboxCheckEnvironmentSourceSpec extends Specification {

    private static final String SHA = 'a'.repeat(40)

    @TempDir
    Path tempDir

    private static Supplier<TaskExecutionEnvironment> noFreshBox() {
        { -> throw new IllegalStateException('no fresh box expected') } as Supplier<TaskExecutionEnvironment>
    }

    private static Workspace attemptWorkspace() {
        def ref = new AttemptCommitRef()
        ref.record(SHA)
        new AttemptCommitWorkspace(ref)
    }

    private static EnvironmentLease leased(TaskExecutionEnvironment env) {
        def stage = new StageDefinition(
                'work', 'p', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
                'i.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        def lease = new EnvironmentLease({ env }, 'gnomish/t', [
            new Segment(AdapterBinding.CONTAINER, [stage])
        ])
        lease.environmentFor('work')
        lease
    }

    def "same-box checks run in the leased environment and close() releases nothing"() {
        given:
        def env = Mock(TaskExecutionEnvironment)
        def source = new SandboxCheckEnvironmentSource(leased(env), noFreshBox(), 'gnomish/t')

        when:
        def acquired = source.acquire(new VerifyCheck.Command('true'), new DirectoryWorkspace(tempDir))
        acquired.close()

        then:
        acquired.environment().is(env)
        0 * env.dispose()
    }

    def "fresh-box without an attempt-commit workspace refuses fail-closed (never a same-box downgrade)"() {
        given:
        def source = new SandboxCheckEnvironmentSource(
                leased(Mock(TaskExecutionEnvironment)), noFreshBox(), 'gnomish/t')

        when:
        source.acquire(
                new VerifyCheck.Command('true', VerifyCheck.VerifyIn.FRESH_BOX),
                new DirectoryWorkspace(tempDir))

        then:
        thrown(CheckEnvironmentUnavailableException)
    }

    // FR13: the fresh box materializes the attempt commit and is disposed on release.
    def "fresh-box checks run in a freshly materialized environment, disposed on close()"() {
        given:
        def fresh = Mock(TaskExecutionEnvironment)
        def source = new SandboxCheckEnvironmentSource(
                leased(Mock(TaskExecutionEnvironment)),
                ({ -> fresh } as Supplier<TaskExecutionEnvironment>),
                'gnomish/t')

        when:
        def acquired = source.acquire(
                new VerifyCheck.Command('true', VerifyCheck.VerifyIn.FRESH_BOX), attemptWorkspace())

        then:
        1 * fresh.materialize('gnomish/t', SHA)
        acquired.environment().is(fresh)

        when:
        acquired.close()

        then:
        1 * fresh.dispose()
    }

    // FR8, FR13, NFR-R1: a failed materialization disposes the half-built fresh box before it
    // surfaces as CheckEnvironmentUnavailableException (an infrastructure failure) — a mutant
    // dropping the dispose() call would leak the container.
    def "a failed fresh-box materialization disposes the fresh environment and surfaces as unavailable"() {
        given:
        def fresh = Mock(TaskExecutionEnvironment)
        def source = new SandboxCheckEnvironmentSource(
                leased(Mock(TaskExecutionEnvironment)),
                ({ -> fresh } as Supplier<TaskExecutionEnvironment>),
                'gnomish/t')

        when:
        source.acquire(new VerifyCheck.Command('true', VerifyCheck.VerifyIn.FRESH_BOX), attemptWorkspace())

        then:
        1 * fresh.materialize('gnomish/t', SHA) >> { throw new RuntimeException('docker died') }
        1 * fresh.dispose()
        def e = thrown(CheckEnvironmentUnavailableException)
        e.message.contains('could not be materialized')
    }
}
