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
 * FR4, FR5, UX2, NFR-O1 of fix-lifecycle-push; FR10 of harden-task-branch-contract — an exhausted
 * delivery fence over the host resume chain: it never blocks a park, fresh or deferred, it only adds
 * the origin-behind line to the report, and it leaves the pending-write marker to the confirm
 * protocol.
 */
class TakeParkFenceExhaustionSpec extends HostResumeReplicationSpecBase {

    // FR5, UX2, NFR-O1: an exhausted fence never blocks the park — the tracker write proceeds and
    // the report the human reads carries the one-line replication note.
    // FR10 of harden-task-branch-contract: the fence takes no part in the pending-write marker —
    //     the outcome commit set it, the tracker write's receipt clears it, exhausted fence or not.
    // Scenario: git/git-task-persistence / Fence exhaustion surfaces instead of blocking
    // Scenario: git/git-task-persistence / Fence exhaustion leaves the marker to the confirm protocol
    def "an exhausted fence parks anyway, with the origin-behind note in the report"() {
        given:
        store.readTaskRecord(_, _) >> Optional.of(recordWith(null, null, false))

        when:
        def result = resume(parkingChain())

        then: 'the outcome commit records the park with the marker owed'
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Escalated, TrackerWrite.OWED)

        then: 'the fence exhausts its attempts and answers a note, touching no lifecycle write'
        1 * branches.fenceParkDelivery(CLONE_DIR, 'PROJ-1') >> new ParkDeliveryVerdict.Undelivered(BEHIND_NOTE)
        0 * lifecycleStore._

        then: 'the park still lands, and its report tells the human origin is behind'
        1 * tracker.park(REF, ParkReason.ESCALATION, {
            it.contains('origin is behind this park')
        })

        then: 'the marker is cleared by the confirm protocol, behind the tracker write'
        1 * lifecycleStore.confirmTerminalWrite('PROJ-1')
        (result as TakeResult.AwaitingHuman).report().contains('origin is behind this park')
    }

    // FR4, FR5: a DEFERRED park — the orphaned-park reconcile, zero engine rounds — is a park write
    // like any other and gets the same fence. The resume-start reconciliation ahead of it is
    // best-effort and swallows its own failure, so without this the human would be told nothing when
    // origin never received the park the tracker is announcing.
    def "a deferred park's re-post carries the fence's origin-behind note"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)
        store.readTaskRecord(_, _) >> Optional.of(recordWith(new RecordedOutcome.Escalated(report), report, true))
        def executor = new ScriptedExecutor([completedRound()])

        when:
        def result = resume(chain(executor))

        then: 'the fence ran for the deferred park, and its verdict shaped the re-posted report'
        1 * branches.fenceParkDelivery(CLONE_DIR, 'PROJ-1') >> new ParkDeliveryVerdict.Undelivered(BEHIND_NOTE)
        1 * tracker.park(REF, ParkReason.ESCALATION, {
            it.contains('origin is behind this park')
        })

        and: 'still zero engine rounds — the fence is a refs read, not a resumed run'
        executor.requests.isEmpty()
        result instanceof TakeResult.AwaitingHuman
    }

    // FR4: a delivered verdict adds nothing to the deferred park's report — the note appears only
    // when origin is genuinely behind.
    def "a deferred park whose fence reports delivered carries no note"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)
        store.readTaskRecord(_, _) >> Optional.of(recordWith(new RecordedOutcome.Escalated(report), report, true))

        when:
        resume(chain(new ScriptedExecutor([completedRound()])))

        then:
        1 * branches.fenceParkDelivery(CLONE_DIR, 'PROJ-1') >> new ParkDeliveryVerdict.Delivered()
        1 * tracker.park(REF, ParkReason.ESCALATION, {
            !it.contains('origin is behind this park')
        })
    }
}
