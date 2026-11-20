package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.agent.runner.AgentRoundRunner
import com.github.oinsio.gnomish.adapter.agent.runner.FakeDialect
import com.github.oinsio.gnomish.adapter.agent.runner.FakeEvent
import com.github.oinsio.gnomish.adapter.agent.runner.StdoutCaps
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * M4 of extract-agent-round-runner (FR1, FR2): the agent round runner with the shared fake dialect of
 * {@code :test-fixtures} through the conformance kit of define-executor-contract, driven in process by
 * {@link BuiltInCandidate} — the mirror interface alone — and each case's runner bound to a host
 * environment over that case's own working copy. The CLI is a {@link FakeProcess} speaking the fake
 * dialect's line format, handed back by the environment's launch: it answers each role it knows as the
 * contract asks and exits 2 with no event in a role it does not; a {@code git} launch is the
 * working-copy check's, answered with a clean view. No production class beyond the runner and its
 * dialect seam is in the path, so the kit judges the seam itself.
 */
@Timeout(60)
class AgentRoundRunnerConformanceSpec extends Specification {

    private static final String CLEAN_VIEW = '# branch.oid abc\u0000# branch.head gnomish/TASK-1\u0000'

    /** What the fake CLI prints per role it knows: its events, in the fake dialect's words. */
    private static final Map<String, String> SCRIPTS = [
        transform: 'hello fake-model-1 session-1\nuse Edit\ndone changed as asked\n',
        verdict: 'hello fake-model-1 session-2\nuse Read\nanswer ' + KitSubjectDouble.VERDICT + '\ndone judged\n',
        decision: 'hello fake-model-1 session-3\nanswer ' + KitSubjectDouble.SELECT_FIRST + '\ndone chosen\n',
    ]

    @TempDir
    Path scratch

    @TempDir
    Path stderrParent

    FakeDialect dialect = new FakeDialect()
    List<List<String>> agentLaunches = []
    List<Duration> waits = []

    // Scenario: executor/agent-round-runner / Second dialect needs no runner change
    def "M4, FR1, FR2: the runner with the fake dialect passes every applicable case, all three roles completing"() {
        given:
        def time = VirtualTimeEquipment.on(new VirtualClock())
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            def argv = builder.command()
            if (argv.first() == 'git') {
                return new FakeProcess(0, CLEAN_VIEW) {}
            }
            agentLaunches << argv
            def script = SCRIPTS[argv.last()]
            script == null ? new FakeProcess(2, '') {} : new FakeProcess(0, script) {}
        } as ProcessLaunch
        List<HostTaskExecutionEnvironment> environments = []
        def binding = { KitCase kase, Path caseCopy ->
            def environment = new HostTaskExecutionEnvironment(caseCopy, time.clock(), ChildEnvAllowlist.none(), launch)
            environment.materialize('gnomish/TASK-1', null)
            environments << environment
            def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, time,
                    ChildEnvAllowlist.none().programGrant(), stderrParent, LastRegisteredCommit.NONE)
            new AgentRoundRunner<FakeEvent>('fake', dialect, environment, equipment,
                    { event -> } as AgentProgressListener, StdoutCaps.DEFAULTS)
        } as CaseBinding

        when:
        def report
        try {
            report = new ConformanceKit([:], { Duration d ->
                waits << d
            } as Sleeper).run(new BuiltInCandidate(dialect.describe(), binding, [:], scratch))
        } finally {
            environments*.dispose()
        }
        def verdicts = report.verdicts()

        then: 'no case failed; the describe, the three roles, the unknown role and the answer cases passed'
        report.failures() == 0
        verdicts.findAll {
            it.value instanceof CaseVerdict.Passed
        }.keySet() == [
            KitCase.DESCRIBE,
            KitCase.SETTINGS_SCHEMA,
            KitCase.TRANSFORM,
            KitCase.VERDICT,
            KitCase.DECISION,
            KitCase.UNKNOWN_ROLE,
            KitCase.MALFORMED_ANSWER
        ] as Set

        and: 'the wire-only cases do not apply in process, nor the poll the describe does not declare'
        KitCase.values().findAll { it.wireOnly() }.every {
            (verdicts[it] as CaseVerdict.NotApplicable).reason().contains('only on the wire')
        }
        (verdicts[KitCase.PENDING_POLL] as CaseVerdict.NotApplicable).reason().contains('poll')
        (verdicts[KitCase.PENDING_REPOLL] as CaseVerdict.NotApplicable).reason().contains('poll')
        waits == []

        and: 'every role went through the one runner and the one dialect, each case its own launch'
        dialect.rendered*.role() == [
            Role.TRANSFORM,
            Role.VERDICT,
            Role.DECISION,
            Role.EFFECT,
            Role.DECISION
        ]
        agentLaunches == [
            ['fake-agent', 'transform'],
            ['fake-agent', 'verdict'],
            ['fake-agent', 'decision'],
            ['fake-agent', 'effect'],
            ['fake-agent', 'decision']
        ]
    }
}
