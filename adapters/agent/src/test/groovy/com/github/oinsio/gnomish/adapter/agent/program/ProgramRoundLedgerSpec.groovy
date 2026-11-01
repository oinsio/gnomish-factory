package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.DescribedExecutors
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.ExecutorCredentialNames
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.json.LedgerJson
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import com.github.oinsio.gnomish.serveobservability.writer.LedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.RoundOutcomeLedgerWriter
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The ledger line of a real {@link ProgramExecutor} round (NFR-O2, UX3 of define-executor-contract;
 * task 4.5 asserted the line at the engine level over a scripted executor): the engine runs a
 * {@code program} stage whose executor is the {@link ProgramStageExecutor} over the host round
 * source, the program is the stand-in preset {@code run-as} run as a real process, and the {@link
 * RoundOutcomeLedgerWriter} writes the round's {@code roundOutcome} line from the engine's own
 * events on a real ledger file — naming the executor with the version its describe answered, the
 * role and the status the program's result file reported.
 */
class ProgramRoundLedgerSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-10-31T09:00:00Z')

    @TempDir
    Path workspace

    @TempDir
    Path home

    @TempDir
    Path stderrParent

    @TempDir
    Path links

    private List<Map> runRound(String section) {
        def clock = new VirtualClock(NOW)
        def serveDir = home.resolve('serve')
        def appender = new RotatingLedgerAppender(
                new LedgerAppender(serveDir.resolveSibling('placeholder'), new LedgerJsonMapper()), serveDir, clock)
        def writer = new RoundOutcomeLedgerWriter(appender, new InstanceInfo('gnome-1', 'host1', '1.0.0'), clock)
        def program = StandIn.link(links.resolve(section), 'run-as')
        def described = new DescribedProgram(ProgramFixtures.declaration([program.toString()]),
        ProgramFixtures.describe(), ['ACME_API_KEY'])
        def executor = new ProgramStageExecutor(PipelineLaw.ofContent(['instructions.md': 'build it\n']), described,
        CliStageExecutor.hostRounds(clock, TestChildEnvironment.hostAllowlist()),
        ProgramFixtures.equipment(stderrParent, ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(clock)))
        def versions = new DescribedExecutors(
                new ExecutorVersions([acme: UntrustedText.subprocess('1')]), ExecutorCredentialNames.none())
        def ports = new EnginePorts(executor, new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), writer, new InMemoryAttemptPersistence(),
                VirtualTimeEquipment.on(clock), AttemptDelivery.assumedDelivered(), versions,
                CliStageExecutor.hostRounds(clock, TestChildEnvironment.hostAllowlist()).identity())
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(1), [ProgramFixtures.stage()]),
        new TaskContext('TASK-7', UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
        TaskState.atStageStart('build'), new DirectoryWorkspace(workspace), ports)
        def file = ObservabilityPaths.ledgerFile(serveDir, LocalDate.ofInstant(NOW, ZoneOffset.UTC))
        Files.readAllLines(file).collect {
            LedgerJson.mapper().readValue(it, Map)
        }
    }

    // Scenario: pipeline/stage-engine / Ledger carries executor identity
    // NFR-O2, UX3: a real program round's ledger line names the executor, its describe version, the role and the status
    def "NFR-O2, UX3: the roundOutcome line of a real program round names #status on every round"() {
        when:
        def lines = runRound(section)

        then:
        lines*.attempt == attempts
        lines[0].type == 'roundOutcome'
        lines[0].taskId == 'TASK-7'
        lines[0].stage == 'build'
        lines[0].executor == 'acme@1'
        lines[0].role == 'transform'
        lines*.status.unique() == [status]

        where:
        section || status | attempts
        'completed' || 'completed' | [0]
        'quality' || 'failed/quality' | [0, 1, 2]
        'limit' || 'failed/limit' | [0]
    }
}
