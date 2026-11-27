package com.github.oinsio.gnomish.serveobservability.writer

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.DescribedExecutors
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorCredentialNames
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.CheckRouting
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.json.LedgerJson
import com.github.oinsio.gnomish.status.CompositeEngineEventListener
import com.github.oinsio.gnomish.status.LoggingEventListener
import com.github.oinsio.gnomish.status.MdcEventListener
import com.github.oinsio.gnomish.testfixtures.JudgeChecks
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * A record names the executor by its registry entry (NFR-O1, FR14 of extract-agent-round-runner;
 * design D12, "The name in the record", "The version of a built-in"): over the engine's own events and
 * every listener the run assembly composes, an {@code agent-cli} stage's round, its {@code judge}
 * check and a described {@code program} check name the entry dispatch resolved, with its version, on
 * the log line, the ledger line and the participant entry; a built-in's version is never pinned.
 */
class RecordedExecutorNameSpec extends Specification implements RotatingLedgerAppenderFixture {

    static final Instant NOW = Instant.parse('2026-11-27T09:00:00Z')
    static final AttemptKey KEY = new AttemptKey('TASK-9', 'build', 0)

    @TempDir
    Path homeDir

    private RoundOutcomeLedgerWriter writer() {
        new RoundOutcomeLedgerWriter(ledgerAppenderFor(homeDir, NOW), new InstanceInfo('gnome-1', 'host1', '1.0.0'),
                new VirtualClock(NOW))
    }

    private List<Map> ledgerLines() {
        def file = ledgerFileFor(homeDir, NOW)
        (Files.exists(file) ? Files.readAllLines(file) : []).collect {
            LedgerJson.mapper().readValue(it, Map)
        }
    }

    // Scenario: pipeline/stage-engine / An agent stage's round is named claude
    // Scenario: pipeline/stage-engine / A judge check's ledger line carries the version
    // NFR-O1, FR14 of extract-agent-round-runner: dispatch resolves an agent-cli block to claude, and
    //     every record names that entry with the built-in's version
    def "an agent stage's round and its judge check name claude@built-in on their lines and participants"() {
        given:
        def persistence = new InMemoryAttemptPersistence()
        def logs = LogCaptureSupport.attach(LoggingEventListener, Level.INFO)

        when:
        runReview([JudgeChecks.agent('criteria.md', 'm')], DescribedExecutors.none(), persistence)

        then: 'the round\'s and the judge check\'s ledger lines'
        ledgerLines().collect { [it.executor, it.role, it.check] } == [
            ['claude@built-in', 'transform', null],
            ['claude@built-in', 'verdict', 0]
        ]

        and: 'the round\'s log line'
        logs.list*.formattedMessage.any { it.contains('executor=claude@built-in, protocolVersion=1, role=transform') }

        and: 'both participant entries name the entry'
        persistence.entries[0].state.attempts()[0].usage()*.executor() == ['claude', 'claude']

        cleanup:
        logs.detach()
    }

    // Scenario: pipeline/stage-engine / A program check's ledger line carries the described version
    // NFR-O1 of extract-agent-round-runner: a described program's check line carries its describe version
    def "a described program check's line carries its describe version"() {
        when:
        runReview([VerifyChecks.external('ci', 'acme-ci', Duration.ZERO, Duration.ZERO, VerifyCheck.TimeoutClass.QUALITY)],
                described(['acme-ci': UntrustedText.subprocess('1')]), new InMemoryAttemptPersistence())

        then:
        ledgerLines()[1].subMap(['executor', 'role']) == [executor: 'acme-ci@1', role: 'verdict']
    }

    // Scenario: pipeline/stage-engine / A built-in's version is not pinned
    // NFR-O1 of extract-agent-round-runner (D12, "The version of a built-in"): shown, never pinned
    def "a task that ran an agent stage and a judge check holds no pin for claude"() {
        given:
        def persistence = new InMemoryAttemptPersistence()

        when:
        runReview([JudgeChecks.agent('criteria.md', 'm')], described(['acme': UntrustedText.subprocess('3')]), persistence)

        then:
        ledgerLines()*.executor == ['claude@built-in', 'claude@built-in']
        persistence.entries*.state*.executorVersions().every { it == ExecutorVersions.none() }
    }

    private static DescribedExecutors described(Map<String, UntrustedText> versions) {
        new DescribedExecutors(new ExecutorVersions(versions), ExecutorCredentialNames.none(), [] as Set)
    }

    /** Runs one agent-cli stage, its checks passing, with every engine listener the run assembly composes. */
    private void runReview(List<VerifyCheck> checks, DescribedExecutors described, InMemoryAttemptPersistence persistence) {
        def stages = new ScriptedExecutor()
        stages.scripted << new ExecutionResult(new ExecutionResult.Completed(), null, ExecutorUsage.none(),
                new ToolTrace(KEY, []), [])
        def rounds = new ScriptedCheckRounds([new Verdict.Pass(null, [])])
        def stage = new StageDefinition('build', 'purpose', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
                'instructions.md', checks, new AutonomyLimits(1), AdvancementMode.AUTO)
        def listener = new CompositeEngineEventListener([new MdcEventListener(), new LoggingEventListener(), writer()])
        def ports = new EnginePorts(CheckRouting.of(stages, rounds, rounds, rounds), listener, persistence,
                VirtualTimeEquipment.on(new VirtualClock(NOW)), AttemptDelivery.assumedDelivered(), described,
                RoundIdentity.inPlace())
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(3), [stage]),
                new TaskContext('TASK-9', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
                TaskState.atStageStart('build'), new FakeWorkspace(), ports)
        MdcEventListener.clearAttemptScope()
    }
}
