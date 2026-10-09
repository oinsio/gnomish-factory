package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification

/**
 * TakeLoadedBranchRoutes.isEscalationDecision (FR9, D3 of add-tracker-port; FR9, D12): the routing
 * predicate that steers a resumed branch to the decision dialog. It is a genuine ESCALATION-kind park
 * — and so a decision — only when the recorded outcome is {@code Escalated} AND the last escalation
 * report is AttemptsExhausted or DecisionNeeded; every other combination (a Paused/Aborted/null
 * outcome that still carries a stale escalation report, or an INFRA-kind report on an Escalated
 * outcome) resumes on the return alone. The pending-marker deferred-park branch and the git-backed
 * dispatch are proven by the reconcile lifecycle specs.
 *
 * <p>The arms of make-checkpoint-gate-durable (FR4, FR7, FR11; design D2, D4, D8) are driven
 * through {@link TakeLoadedBranchRoutes#route} over mocked mechanics: what is written, in which
 * order, and that a gate whose park was lost runs nothing.
 *
 * FR9, D3 of add-tracker-port; FR9, D12, FR10 of add-claim-heartbeat; FR4, FR7, FR11 of
 * make-checkpoint-gate-durable.
 */
class TakeLoadedBranchRoutesSpec extends Specification implements RunChainFakes {

    ResumeMechanics<ResumeBootstrap> mechanics = Mock(ResumeMechanics)
    Tracker tracker = Mock(Tracker)

    private TakeResult route(ResumeBootstrap branch, TaskState recorded) {
        mechanics.loadBranch(_, 'PROJ-1') >> branch
        mechanics.readFinalState(branch) >> recorded
        tracker.fetchTask(_) >> heldByUs()
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit), new ClaimEpochBook())
        new TakeLoadedBranchRoutes<ResumeBootstrap>(mechanics, new TakeDecisionResume<ResumeBootstrap>(mechanics), git,
                VirtualTimeRetries.terminalWrite())
                .route(takeOrder(heldByUs(), tracker))
    }

    private static TaskState exhaustedAt(String stage) {
        def gated = CheckpointMechanicsFixtures.gatedAt(stage)
        new TaskState(new Position.AtStage(stage), 1, gated.attempts(), gated.totals())
    }

    private static RecordedOutcome escalated() {
        new RecordedOutcome.Escalated(new EscalationReport.AttemptsExhausted(3))
    }

    private static RecordedOutcome paused() {
        new RecordedOutcome.Paused('build')
    }

    private static ResumeBootstrap bootstrap(RecordedOutcome outcome, boolean pending) {
        new ResumeBootstrap(
                'PROJ-1',
                new TaskContext('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                outcome,
                null,
                Path.of('/tmp/unused'),
                'gnomish/PROJ-1',
                'base-commit',
                pending,
                BasePin.UNPINNED)
    }

    // FR10, D10, NFR-C1: a branch is an orphaned park to reconcile ONLY when the tracker-write marker
    //     is still pending AND the recorded outcome is a park (Escalated/Paused); a cleared marker or
    //     a non-park outcome is not.
    def "#label is an orphaned park: #orphaned"() {
        expect:
        TakeLoadedBranchRoutes.isOrphanedPark(bootstrap(outcome, pending)) == orphaned

        where:
        label | outcome | pending || orphaned
        'pending Escalated' | escalated() | true || true
        'pending Paused' | paused() | true || true
        'cleared Escalated' | escalated() | false || false
        'cleared Paused' | paused() | false || false
        'pending Completed' | new RecordedOutcome.Completed() | true || false
        'pending null outcome' | null | true || false
    }

    // FR9, D3, D12: only an Escalated outcome whose report is an ESCALATION kind is a decision park.
    def "#label is a decision park: #decision"() {
        expect:
        TakeLoadedBranchRoutes.isEscalationDecision(outcome, report) == decision

        where:
        label | outcome | report || decision
        'Escalated + AttemptsExhausted' | escalated() | new EscalationReport.AttemptsExhausted(3) || true
        'Escalated + DecisionNeeded' | escalated() | new EscalationReport.DecisionNeeded(UntrustedText.agent('Q?'), [
            UntrustedText.agent('a'),
            UntrustedText.agent('b')
        ]) || true
        'Escalated + INFRA CannotExecute' | escalated() | new EscalationReport.CannotExecute(UntrustedText.subprocess('adapter crashed'), []) || false
        'Escalated + no report' | escalated() | null || false
        'Paused + stale AttemptsExhausted' | paused() | new EscalationReport.AttemptsExhausted(3) || false
        'null outcome + AttemptsExhausted' | null | new EscalationReport.AttemptsExhausted(3) || false
        'null outcome + no report' | null | null || false
    }

    // FR4: a returned checkpoint — the gate's own park recorded, its marker cleared — is opened by
    //      the approval write BEFORE the engine runs, and the run continues from the approved state
    def "FR4: a returned gate is approved, then continued from the approved state"() {
        given:
        def approved = TaskState.atStageStart('test')
        def continued = new TakeResult.AwaitingHuman(approved, ParkReason.CHECKPOINT, 'next gate')
        def calls = []

        when:
        def result = route(bootstrap(paused(), false), CheckpointMechanicsFixtures.gatedAt('build'))

        then:
        1 * mechanics.approveCheckpoint(_, _) >> {
            calls << 'approve'; approved
        }
        1 * mechanics.resumeWithoutDecision(_, _, approved) >> {
            calls << 'run'; continued
        }
        0 * mechanics.resumeFrom(*_)
        0 * mechanics.recordPark(*_)
        0 * tracker.park(*_)
        calls == ['approve', 'run']
        result.is(continued)
    }

    // FR4, FR11, D8: a gate whose park was never recorded is parked, not run — the owed Paused
    //      outcome is recorded (with its marker), delivered on the tracker as a checkpoint, and the
    //      marker cleared on receipt; nothing is approved, consumed or run
    def "FR11: a gate whose park was lost (#label) delivers the park and runs nothing"() {
        given:
        def gated = CheckpointMechanicsFixtures.gatedAt('build')
        def calls = []

        when:
        def result = route(bootstrap(outcome, false), gated)

        then:
        1 * mechanics.recordPark(_, _, new TaskOutcome.Paused(gated, 'build')) >> {
            calls << 'record'
            new ParkDeliveryVerdict.Delivered()
        }
        1 * tracker.park(REF, ParkReason.CHECKPOINT, {
            it.contains('build')
        }) >> {
            calls << 'park'
        }
        1 * mechanics.confirmTerminalWrite(_, _) >> { calls << 'receipt' }
        0 * mechanics.approveCheckpoint(*_)
        0 * mechanics.resumeFrom(*_)
        0 * mechanics.resumeWithoutDecision(*_)
        0 * mechanics.resumeDecided(*_)
        calls == ['record', 'park', 'receipt']
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.CHECKPOINT

        where:
        label | outcome
        'no outcome' | null
        'stale park of another stage' | new RecordedOutcome.Paused('lint')
        'stale escalation' | escalated()
    }

    // FR7, D4: an INFRA-kind return is consumed by the resumed write, carrying the attempt reset,
    //      before the engine runs — and the engine runs from exactly that reset state
    def "FR7: an infrastructure return lands the resumed write with the reset, then continues"() {
        given:
        def exhausted = exhaustedAt('build')
        def report = new EscalationReport.CannotVerify(new CheckRef(0, UntrustedText.manifest('tests')),
                UntrustedText.subprocess('down'), UntrustedText.subprocess(''))
        def branch = new ResumeBootstrap('PROJ-1', CheckpointMechanicsFixtures.context(),
                new RecordedOutcome.Escalated(report), report, Path.of('/tmp/unused'), 'gnomish/PROJ-1',
                'base-commit', false, BasePin.UNPINNED)
        def calls = []

        when:
        route(branch, exhausted)

        then:
        1 * mechanics.resumeFrom(_, branch, exhausted.resetAttempts()) >> {
            calls << 'resumed'
        }
        1 * mechanics.resumeWithoutDecision(_, branch, exhausted.resetAttempts()) >> {
            calls << 'run'; null
        }
        0 * mechanics.approveCheckpoint(*_)
        calls == ['resumed', 'run']
    }

    // FR7: an outcome that burned no budget (an abort retried) is still consumed by the resumed
    //      write, with the state kept as recorded
    def "FR7: an aborted visit is consumed with its recorded state, then continued"() {
        given:
        def recorded = exhaustedAt('build')
        def branch = bootstrap(new RecordedOutcome.Aborted('build#0', UntrustedText.subprocess('box died')), false)
        def calls = []

        when:
        route(branch, recorded)

        then:
        1 * mechanics.resumeFrom(_, branch, recorded) >> { calls << 'resumed' }
        1 * mechanics.resumeWithoutDecision(_, branch, recorded) >> {
            calls << 'run'; null
        }
        calls == ['resumed', 'run']
    }

    // FR7: a bare return of an exhausted stage lands the reset through the resumed write before
    //      the decided resume runs — the reset is never handed over in memory alone
    def "FR7: a bare AttemptsExhausted return writes the reset before resuming"() {
        given:
        def exhausted = exhaustedAt('build')
        def branch = new ResumeBootstrap('PROJ-1', CheckpointMechanicsFixtures.context(), escalated(),
                new EscalationReport.AttemptsExhausted(3), Path.of('/tmp/unused'), 'gnomish/PROJ-1',
                'base-commit', false, BasePin.UNPINNED)
        def calls = []

        when:
        route(branch, exhausted)

        then:
        1 * tracker.collectDecisions(REF) >> []
        1 * mechanics.resumeFrom(_, branch, exhausted.resetAttempts()) >> {
            calls << 'resumed'
        }
        1 * mechanics.resumeDecided(_, branch, branch.context(), exhausted.resetAttempts()) >> {
            calls << 'run'
            null
        }
        0 * mechanics.appendDecision(*_)
        calls == ['resumed', 'run']
    }

    // FR7: a tip with no recorded outcome has nothing to consume — no resumed write, the run
    //      continues from the recorded state
    def "FR7: a tip with no outcome continues without a resumed write"() {
        given:
        def recorded = TaskState.atStageStart('build')
        def branch = bootstrap(null, false)

        when:
        route(branch, recorded)

        then:
        0 * mechanics.resumeFrom(*_)
        1 * mechanics.resumeWithoutDecision(_, branch, recorded)
    }
}
