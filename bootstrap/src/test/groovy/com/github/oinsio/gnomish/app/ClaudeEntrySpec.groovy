package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.program.GuardedExecutor
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.ExecutorName
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14 of extract-agent-round-runner (design D2, D12, D13): the run's registry holds the {@code
 * claude} entry {@link ClaudeEntry} composes — the Claude dialect's describe, a stage half named
 * {@code claude}, and a check half bound behind the guard probe's one owner: in a fresh box per request
 * at the attempt commit, whose guard admits the dialect's egress host, in a run that has boxes, and on
 * the host over the working copy in a run that has none. No feature starts a request: the halves'
 * answers through a dialect are {@code AgentStageRoundsSpec}'s and {@code AgentCheckBindingSpec}'s,
 * over the fake dialect.
 */
class ClaudeEntrySpec extends Specification {

    @TempDir
    Path workingCopy

    def clock = new VirtualClock()

    def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(clock),
    TestChildEnvironment.hostAllowlist().programGrant(), Path.of('/tmp'), LastRegisteredCommit.NONE)

    private static PipelineDefinition definition() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', [VerifyChecks.command('true')], new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
    }

    private RegistryEntry entry(FreshBoxSource boxes = null) {
        ClaudeEntry.of(new FactoryProperties(null, null, null, null), ChildEnvAllowlist.none(),
                PipelineLaw.ofContent([:]), equipment, boxes)
    }

    // Scenario: pipeline/pipeline-config / An agent block resolves to the reserved executor name
    def "FR14, D12: the registry resolves the reserved name claude to the entry whose describe is the Claude dialect's"() {
        given:
        def registry = ExecutorRegistry.of(DescribedLaws.none(), definition(), PipelineLaw.ofContent([:]), equipment,
        BuiltInEntriesFixture.of(clock), null, entry())

        when:
        def resolved = registry.resolve(new ExecutorName(BuiltInExecutors.CLAUDE))

        then: 'the entry named claude, serving stage rounds under its name, with the three roles'
        resolved.name() == new ExecutorName('claude')
        resolved.rounds().name() == 'claude'
        resolved.describe().roles() == [
            Role.TRANSFORM,
            Role.VERDICT,
            Role.DECISION
        ]

        and: 'the in-process dialect describes itself as a built-in, with its one egress host'
        resolved.describe().version().forLog() == 'built-in'
        resolved.describe().egress() == ['api.anthropic.com']
    }

    // FR14, FR15, D2: a host run binds a check request on the host, behind the guard probe
    def "FR14, D2: in a run without boxes the check half binds on the host over the working copy, behind the guard probe"() {
        when:
        def bound = entry().bound(VerifyChecks.command('true'), CheckRef.of(0, VerifyChecks.command('true')),
                new DirectoryWorkspace(workingCopy))
        bound.release().run()

        then:
        bound.executor() instanceof GuardedExecutor
        bound.executor().describe().roles().contains(Role.VERDICT)
    }

    // FR14, FR15, D2: a boxed run binds a check request in a fresh box at the attempt commit, behind the guard probe
    def "FR14, D2: in a run with boxes the check half binds a fresh box at the attempt commit admitting the dialect's host"() {
        given:
        List<Map> asked = []
        int released = 0
        def box = Stub(TaskExecutionEnvironment)
        FreshBoxSource boxes = { String commit, List<String> egress ->
            asked << [commit: commit, egress: egress]
            new CheckEnvironmentSource.Acquired() {
                        TaskExecutionEnvironment environment() {
                            box
                        }

                        void close() {
                            released++
                        }
                    }
        } as FreshBoxSource

        when:
        def bound = entry(boxes).bound(VerifyChecks.command('true'), CheckRef.of(0, VerifyChecks.command('true')),
                { -> 'abc123' } as AttemptCommitWorkspace)
        bound.release().run()

        then:
        bound.executor() instanceof GuardedExecutor
        asked == [
            [commit: 'abc123', egress: ['api.anthropic.com']]
        ]
        released == 1
    }
}
