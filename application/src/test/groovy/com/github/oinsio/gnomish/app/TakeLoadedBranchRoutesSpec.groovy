package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
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
 * FR9, D3 of add-tracker-port; FR9, D12, FR10 of add-claim-heartbeat.
 */
class TakeLoadedBranchRoutesSpec extends Specification {

    private static RecordedOutcome escalated() {
        new RecordedOutcome.Escalated(new EscalationReport.AttemptsExhausted(3))
    }

    private static RecordedOutcome paused() {
        new RecordedOutcome.Paused('build')
    }

    private static ResumeBootstrap bootstrap(RecordedOutcome outcome, boolean pending) {
        new ResumeBootstrap(
                'PROJ-1',
                new TaskContext('PROJ-1', 'title', 'body', List.<Decision> of()),
                outcome,
                null,
                Path.of('/tmp/unused'),
                'gnomish/PROJ-1',
                'base-commit',
                pending)
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
        'Escalated + DecisionNeeded' | escalated() | new EscalationReport.DecisionNeeded('Q?', ['a', 'b']) || true
        'Escalated + INFRA CannotExecute' | escalated() | new EscalationReport.CannotExecute('adapter crashed') || false
        'Escalated + no report' | escalated() | null || false
        'Paused + stale AttemptsExhausted' | paused() | new EscalationReport.AttemptsExhausted(3) || false
        'null outcome + AttemptsExhausted' | null | new EscalationReport.AttemptsExhausted(3) || false
        'null outcome + no report' | null | null || false
    }
}
