package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.oinsio.gnomish.app.take.FinishTransition
import com.github.oinsio.gnomish.app.take.ParkTransition
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.app.take.TerminalTransitions
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * {@link SlotWiringFactory}: the one construction of a {@link SlotWiring} that {@code take} and
 * {@code serve} used to spell each by hand (design D9 of collapse-composition-roots) — the built
 * wiring carries the factory's equipment and the caller's own bound tracker, git and heartbeat.
 *
 * Implements FR1 of collapse-composition-roots.
 */
class SlotWiringFactorySpec extends Specification implements RunChainFakes {

    private static final TrackerConfig CONFIG = new TrackerConfig('github', 5)

    def "the wiring carries the factory's equipment and the caller's bound tracker, git and heartbeat"() {
        given:
        def plain = Mock(RunAssembly)
        def listened = Mock(RunAssembly)
        def time = VirtualTimeEquipment.create()
        def sourced = Stub(RunAssembly) {
            timeEquipment() >> time
        }
        def source = Stub(PipelineSource)
        def support = ContainerTakeSupportFixture.hostOnly()
        def tracker = Stub(Tracker)
        def adapterFactory = Stub(TrackerAdapterFactory) {
            credentialEnvVars(CONFIG) >> ['GNOMISH_GITHUB_TOKEN']
        }
        def bound = new BoundTracker(pipeline(), DEFAULT_TRUSTED_BASE, CONFIG, adapterFactory, tracker, INSTANCE)
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit), new ClaimEpochBook())
        def heartbeat = TakeHeartbeat.forRun(tracker, CONFIG, VirtualTimeEquipment.on(FIXED_CLOCK, { Duration d -> } as Sleeper))
        def factory = new SlotWiringFactory(plain, RegisteredCloneFixture.provider(CLONE), 'taskId', support, source)

        when:
        def wiring = factory.slotWiring(bound, git, heartbeat)

        then: "the assembly is the heartbeat-listening copy over the factory's pipeline source"
        1 * plain.withExtraListener(heartbeat.progress()) >> listened
        1 * listened.withPipelineSource(source) >> sourced
        wiring.assembly().is(sourced)

        and: "the caller's git and heartbeat tenure, the factory's equipment"
        wiring.git().is(git)
        wiring.tenure() == heartbeat.tenure()
        wiring.registeredClone() == CLONE
        wiring.taskIdMdcKey() == 'taskId'
        wiring.containerTakeSupport().is(support)

        and: "FR18 of supervise-daemon-loops-and-embed-dashboard: the slot's one time is its assembly's, and the retry is measured on it"
        wiring.time().is(time)
        wiring.terminalWriteRetry().time().is(time)
        wiring.terminalWriteRetry().bound() == TerminalWriteRetry.DEFAULT_BOUND

        and: "the bound tracker's members: the abort handler writes to the tracker the slot claims through, on the slot's clock"
        wiring.abort().handler().tracker().is(tracker)
        wiring.abort().handler().clock().is(time.clock())
        wiring.abort().threshold() == 5
        wiring.credentialEnvVarsToScrub() == ['GNOMISH_GITHUB_TOKEN']
        wiring.trustedBase().is(DEFAULT_TRUSTED_BASE)
    }

    // FR18, FR22 of supervise-daemon-loops-and-embed-dashboard (design D22, the identity of the
    // dispatch row; task 3.9): the slot runs on one time source, its assembly's. A run that ends
    // against a tracker that stays down waits out the retry bound on the assembly's clock, and an
    // aborted run stamps its abort from that same clock. A retry or an abort handler built on any
    // other source leaves the assembly's clock where it was, or stamps another instant: red.
    def "a run of the slot dispatches its terminal write and its abort stamp on the assembly's one clock"() {
        given: 'a slot assembly on a virtual equipment starting at a distinctive instant'
        def start = Instant.parse('2026-03-04T05:06:07Z')
        def clock = new VirtualClock(start)
        RunAssembly assembly
        assembly = Stub(RunAssembly) {
            timeEquipment() >> VirtualTimeEquipment.on(clock)
            withExtraListener(_) >> { assembly }
            withPipelineSource(_) >> { assembly }
        }
        def recorded = []
        def tracker = Stub(Tracker) {
            fetchTask(REF) >> heldByUs()
            park(*_) >> {
                throw new TrackerUnavailableException('tracker down')
            }
            recordAbort(REF, _) >> { args ->
                recorded << (args[1] as AbortRecord)
            }
        }
        def bound_ = new BoundTracker(pipeline(), DEFAULT_TRUSTED_BASE, CONFIG, Stub(TrackerAdapterFactory), tracker, INSTANCE)
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit), new ClaimEpochBook())
        def heartbeat = TakeHeartbeat.forRun(tracker, CONFIG, VirtualTimeEquipment.on(FIXED_CLOCK, { Duration d -> } as Sleeper))
        def dispatch = new SlotWiringFactory(assembly, RegisteredCloneFixture.provider(CLONE), 'taskId',
                ContainerTakeSupportFixture.hostOnly(), Stub(PipelineSource)).slotWiring(bound_, git, heartbeat).outcomeDispatch()
        def transitions = new TerminalTransitions(
                new ParkTransition.Fresh({
                    new ParkDeliveryVerdict.Delivered()
                } as ParkTransition.ParkIntent, {}),
                new FinishTransition.Fresh({}, {}))
        def logs = LogCaptureSupport.attach(TakeEscalationExit)

        when: 'a run of the slot aborts'
        def aborted = dispatch.dispatch(
                new TaskOutcome.Aborted(TaskState.atStageStart('build'), new AttemptKey('PROJ-1', 'build', 0), UntrustedText.subprocess('lost')),
                CONTEXT_OF_RUN, 'gnomish/PROJ-1', takeOrder(heldByUs(), tracker), transitions)

        then: 'the abort is stamped from the assembly clock'
        aborted instanceof TakeResult.Aborted
        recorded*.at() == [start]

        when: 'a run of the slot escalates against a tracker that stays down'
        def parked = dispatch.dispatch(
                new TaskOutcome.Escalated(TaskState.atStageStart('build'), new EscalationReport.AttemptsExhausted(3)),
                CONTEXT_OF_RUN, 'gnomish/PROJ-1', takeOrder(heldByUs(), tracker), transitions)

        then: 'it gave up once the retry bound elapsed on that same clock'
        Duration.between(start, clock.instant()) >= TerminalWriteRetry.DEFAULT_BOUND
        parked instanceof TakeResult.AwaitingHuman

        and: 'the give-up is the deferred park the reconcile path completes later'
        logs.list*.formattedMessage.any {
            it.startsWith(OperatorEvent.PARK_UNWRITTEN_AFTER_RETRIES.head())
        }

        cleanup:
        logs?.detach()
    }

    private static final TaskContext CONTEXT_OF_RUN = new TaskContext(
    'PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of())
}
