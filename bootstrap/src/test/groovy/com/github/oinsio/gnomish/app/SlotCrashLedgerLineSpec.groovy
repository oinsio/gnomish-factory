package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.tracker.*
import com.github.oinsio.gnomish.app.serve.*
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.*
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import com.github.oinsio.gnomish.serveobservability.writer.LedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir
/**
 * FR18, M8 of add-sandbox-base-image (design D20): the slot boundary writes the ledger line of a
 * slot that ended with no result — {@code aborted}, no stage, no attempt, no tokens, the wall time
 * — once, only when the slot wrote none, and a failure of that write is suppressed on the crash,
 * whose own line is still logged. Over the real slot runner and the real ledger.
 */
class SlotCrashLedgerLineSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ServeObservabilityFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final InstanceId INSTANCE = new InstanceId('gnomish-factory', 'cl0001')

    @TempDir
    Path tempDir

    def git = TaskGitFixture.real()
    def clock = new VirtualClock()
    def slotLedger = new SlotLedger(1, clock)
    Path cloneDir
    Throwable fetchFailure

    InMemoryTracker tracker = new InMemoryTracker() {
        @Override
        TrackerTask fetchTask(TaskRef ref) {
            if (fetchFailure != null) {
                throw fetchFailure
            }
            super.fetchTask(ref)
        }
    }

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        writeMinimalProject(cloneDir)
        commitAll(cloneDir, 'init')
        addConvergedOrigin(cloneDir, tempDir)
        new InMemoryTrackerHarness(tracker).seed(REF,
                new TaskSnapshot(REF.id(), UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), AbortFacts.none())
        tracker.claim(REF, INSTANCE.value())
        slotLedger.assign(REF)
    }

    // Scenario: observability/serve-observability / A crashed slot leaves one line
    def "a slot that dies on #kind leaves one aborted taskOutcome line with no stage, attempt or tokens"() {
        given:
        fetchFailure = failure

        when:
        runner(conditions()).run(REF)

        then:
        def lines = ledgerLines()
        lines.size() == 1
        lines[0].get('type').asText() == 'taskOutcome'
        lines[0].get('taskId').asText() == 'PROJ-1'
        lines[0].get('outcome').asText() == 'aborted'
        lines[0].get('stage').isNull()
        lines[0].get('attemptsUsed').asInt() == 0
        lines[0].get('tokensByModel').size() == 0

        where:
        kind | failure
        'an Error' | new AssertionError('slot body broke')
        'a runtime failure' | new IllegalStateException('tracker lost the task')
    }

    def "a failure after the slot's own line was written leaves no second line"() {
        given: 'a refusal the slot ends on, whose report then throws: the set holds no such condition'
        gitOutput(cloneDir, 'refs', 'migrate', '--ref-format=files')
        def remoteOnly = new DaemonConditions(conditions().remote(), [])

        when:
        runner(remoteOnly).run(REF)

        then: 'the typed result writes no taskOutcome line, and the crash arm writes none either'
        ledgerLines().isEmpty()
    }

    def "a crash line that cannot be written is suppressed on the crash, which is still logged"() {
        given: 'a ledger clock behind the slot, so the crash line has a negative wall time and is refused'
        fetchFailure = new IllegalStateException('tracker lost the task')
        clock.advance(Duration.ofMinutes(-5))
        def logs = LogCaptureSupport.attach(TakeSlotRunner)

        when:
        runner(conditions()).run(REF)

        then:
        ledgerLines().isEmpty()
        def crash = logs.list.find {
            it.formattedMessage.contains('crashed uncaught')
        }
        crash.level == Level.ERROR
        crash.throwableProxy.suppressed*.className == [IllegalArgumentException.name]

        cleanup:
        logs.detach()
    }

    private DaemonConditions conditions() {
        RemoteOutageGates.forServe(BaseRefGit.UNWIRED, cloneDir,
                new ServeProperties(0, null, null, null, null, null, null, null, null, null), new VirtualClock(),
                DirtyNotifier.NOOP, RemoteOutageLedgerSink.NONE, { kind ->
                    Optional.empty()
                } as PreconditionRecheck)
    }

    private TakeSlotRunner runner(DaemonConditions conditions) {
        def assembly = newAssembly(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8'),
                FakeAgentSupport.propertiesFor('plain-round'), VirtualTimeEquipment.create())
        def wiring = new SlotWiring(assembly, git, RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir),
                'taskId', new AbortFuse(new AbortHandler(tracker, assembly.timeEquipment().clock()), 3), [],
                ContainerTakeSupportFixture.hostOnly(), new ClaimTenure(ClaimBeat.NONE, new ClaimLossFlag()),
                new TrustedBaseContext(BaseDefinition.none(), new DefaultBranch(currentBranch(cloneDir))))
        def slotRunner = new TakeSlotRunner(wiring, new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE, conditions)
        def appender = new RotatingLedgerAppender(
                new LedgerAppender(tempDir.resolve('placeholder'), new LedgerJsonMapper()), serveDir(), clock)
        slotRunner.attachLedgerWriter(new TaskOutcomeLedgerWriter(
                        slotLedger, appender, new InstanceInfo('gnomish-cl0001', 'worker-1', '0.1.0'), clock))
        slotRunner
    }

    private Path serveDir() {
        tempDir.resolve('serve')
    }

    private List ledgerLines() {
        def file = ObservabilityPaths.ledgerFile(serveDir(), LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
        Files.exists(file) ? readLedgerLines(file) : []
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'purpose', [], [],
            new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
            'stages/build/instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
    }
}
