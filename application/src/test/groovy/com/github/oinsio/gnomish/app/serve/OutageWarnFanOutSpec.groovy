package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.FreshClaimBaseBindingLogRun
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.Designator
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * "One failure, one log" (.claude/rules/logging.md) across the three emitters a dead remote passes
 * through. {@code RemoteOutageServeEndToEndSpec} captures only {@link RemoteOutageGate}'s own
 * logger, so it cannot see a second WARN written elsewhere for the same fault; this spec walks ONE
 * released task through the exact production sequence a slot performs — {@code
 * FreshClaimBaseBinding#bind} (run through {@link
 * com.github.oinsio.gnomish.app.FreshClaimBaseBindingLogRun}, since that class is package-private
 * to a sibling package) returns the {@code InfrastructureUnavailable}, {@code
 * TakeSlotRunner#run} hands it to {@link RemoteOutageGate#openOnFailure} and then to {@link
 * SlotOutcomeLog#detail} (TakeSlotRunner.java: signalRemoteOutageGate then outcomeLog.detail) — and
 * counts what the whole console sees.
 *
 * <p>The two WARNs that remain are two different faults: the released claim, decided and named by
 * the binding, and the daemon-level gate transition. The slot's own restatement of the first
 * (retired code {@code GF144}) is DEBUG detail.
 *
 * <p>NFR-O1, FR9, FR14 of add-base-ref-resolution.
 */
class OutageWarnFanOutSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final Path ROOT = Paths.get('/repo')

    private BaseRefGit baseRefGit = Mock()
    private Tracker tracker = Mock()

    private RemoteOutageGate gate() {
        def suppressor = new RepeatSuppressor(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMinutes(5))
        new RemoteOutageGate(
                baseRefGit, ROOT, new VirtualClock(), new Random(1), Duration.ofSeconds(30), Duration.ofMinutes(10),
                new RemoteOutageWiring('origin', suppressor, Duration.ofHours(1), {}, { ignored -> }))
    }

    def "one dead-remote release states each fault once: the binding's WARN and the gate's, never a third"() {
        given: 'the whole console, so every emitter in the sequence is counted'
        def console = LogCaptureSupport.attach('ROOT', Level.INFO)
        def task = new TrackerTask(
                REF, new TaskSnapshot('PROJ-1', 'title', 'body'), new TrackerTaskState.Ready(), AbortFacts.none(),
                false, TaskDesignators.of('base', Designator.absent()))
        def outcomeLog = new SlotOutcomeLog(LoggerFactory.getLogger(TakeSlotRunner))
        def gate = gate()

        when: 'the slot resolves its base against an origin that never answers, then reports the outcome'
        FreshClaimBaseBindingLogRun.bind(baseRefGit, ROOT, task, tracker)
        // The Released outcome's own result, restated: FreshClaimBaseBinding.Released is
        // package-private, and what this spec counts is the log fan-out, not the record's shape
        // (FreshClaimBaseBindingSpec already pins the returned variant and its text).
        def result = new TakeResult.InfrastructureUnavailable(
                "Task PROJ-1 claim released (the reaper returns it to Ready after the claim TTL): origin did not answer the refresh of its resolved base"
                + " ref 'main': connection timed out")
        gate.openOnFailure(result.reason())
        outcomeLog.detail(REF, result)

        then:
        1 * baseRefGit.refresh(ROOT, 'main') >> new BaseRefreshOutcome.Unavailable('connection timed out')
        1 * tracker.release(REF)

        and: 'the released claim is stated once by the layer that decided it, the transition once by the gate — and the slot adds no third line for either'
        console.list.findAll {
            it.level == Level.WARN
        }.collect {
            it.formattedMessage.take(7)
        }
        == [
            OperatorEvent.FRESH_CLAIM_BASE_REFRESH_UNAVAILABLE.head().trim(),
            OperatorEvent.REMOTE_OUTAGE_GATE_OPENED.head().trim()
        ]

        cleanup:
        console.detach()
    }
}
