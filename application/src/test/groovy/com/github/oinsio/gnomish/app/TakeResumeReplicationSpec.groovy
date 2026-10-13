package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.*
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.UnaryOperator
import spock.lang.Specification
import spock.lang.TempDir
/**
 * FR3, FR4, M2 of fix-lifecycle-push over the host resume chain: where a resumed run touches the
 * remote. Resume start reconciles origin up to the local branch tip; a fresh park runs the delivery
 * fence BEFORE the tracker write announcing it; a delivered run runs no fence. What a park reports
 * when the fence could not deliver is {@link TakeParkFenceExhaustionSpec}.
 *
 * <p>Split out of {@code TakeResumeRoutingSpec}, which owns the routing table itself.
 */
class TakeResumeReplicationSpec extends HostResumeReplicationSpecBase {

    // FR3: resume start is a touchpoint — after the worktree's own divergence reconcile pulls local
    // up to origin, this pushes origin up to local, delivering a commit an earlier instance recorded
    // but never got pushed.
    def "reconciles the remote at resume start"() {
        given:
        store.readTaskRecord(_, _) >> Optional.of(recordWith(null, null, false))
        branches.fenceParkDelivery(_, _) >> new ParkDeliveryVerdict.Delivered()

        when:
        resume(chain(new ScriptedExecutor([completedRound()])))

        then:
        1 * branches.reconcileRemote(CLONE_DIR, 'PROJ-1', 'resume-start')
    }

    // NG6, FR4: a delivered run has no pending tracker write to protect and no park for another
    // instance to reconcile from, so it stays purely best-effort and never spends the fence's extra
    // refs read. {@code TakeFenceScopeSpec} owns that scope rule for every non-park outcome.
    // Scenario: git/git-task-persistence / Completed and Aborted outcomes run no fence
    def "a delivered run runs no delivery fence"() {
        given:
        store.readTaskRecord(_, _) >> Optional.of(recordWith(null, null, false))

        when:
        def result = resume(chain(new ScriptedExecutor([completedRound()])))

        then:
        0 * branches.fenceParkDelivery(_, _)
        result instanceof TakeResult.Delivered
    }

    // M2, FR4: the park's commit must be delivered to origin BEFORE the tracker announces the park —
    // otherwise another instance reconciles from an origin that lacks the outcome. The ordering is
    // asserted directly: the fence's invocation precedes the tracker write's.
    // Scenario: git/git-task-persistence / Fence delivers before the park lands
    def "the delivery fence runs before the park's tracker write"() {
        given:
        store.readTaskRecord(_, _) >> Optional.of(recordWith(null, null, false))

        when:
        resume(parkingChain())

        then: 'FR10: the park\'s durable intent — the outcome commit — is recorded first of all'
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Escalated, TrackerWrite.OWED)

        then: 'the fence is asked next'
        1 * branches.fenceParkDelivery(CLONE_DIR, 'PROJ-1') >> new ParkDeliveryVerdict.Delivered()

        then: 'and only then does the tracker learn about the park'
        1 * tracker.park(REF, ParkReason.ESCALATION, {
            !it.contains('origin is behind this park')
        })
    }
}
