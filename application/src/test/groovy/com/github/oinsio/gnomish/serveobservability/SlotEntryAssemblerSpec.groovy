package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.lease.HeartbeatProgress
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import spock.lang.Specification

/**
 * Verifies {@link SlotEntryAssembler}: it combines {@link SlotLedger#occupiedEntries()}
 * (taskId + since) with {@link HeartbeatProgress#progressFor(String)} (stage + attempt) into
 * one {@link SlotEntry} per occupied slot — the snapshot's {@code slots} entries (FR6), whose
 * {@code stage}/{@code attempt} may lag up to {@code intervalSeconds} behind occupancy itself
 * (design D11), since only assign/release are immediate-write triggers, never a stage
 * transition.
 *
 * FR6, D11 of add-serve-observability.
 */
class SlotEntryAssemblerSpec extends Specification {

    def clock = new SystemClock()

    def "no occupied slots produce no entries"() {
        given:
        def ledger = new SlotLedger(2, clock)
        def progress = new HeartbeatProgress()

        expect:
        SlotEntryAssembler.assemble(ledger, progress).isEmpty()
    }

    def "an occupied slot with a reported stage/attempt carries them into its entry"() {
        given:
        def ledger = new SlotLedger(2, clock)
        def progress = new HeartbeatProgress()
        def task = new TaskRef('task-1')
        ledger.acquire()
        ledger.assign(task)
        progress.onEvent(new EngineEvent.AttemptStarted(new AttemptKey('task-1', 'implement', 2)))

        when:
        def entries = SlotEntryAssembler.assemble(ledger, progress)

        then:
        entries.size() == 1
        def entry = entries[0]
        entry.taskId() == 'task-1'
        entry.stage() == 'implement'
        entry.attempt() == 2
        entry.since() == ledger.occupiedEntries().iterator().next().since()
    }

    def "an occupied slot with no engine event yet reports a null stage (pending)"() {
        given:
        def ledger = new SlotLedger(1, clock)
        def progress = new HeartbeatProgress()
        def task = new TaskRef('task-2')
        ledger.acquire()
        ledger.assign(task)

        when:
        def entries = SlotEntryAssembler.assemble(ledger, progress)

        then:
        entries.size() == 1
        entries[0].taskId() == 'task-2'
        entries[0].stage() == null
        entries[0].attempt() == 0
    }

    def "an occupied slot whose progress resolved to pipeline end reports a null stage"() {
        given:
        def ledger = new SlotLedger(1, clock)
        def progress = new HeartbeatProgress()
        def task = new TaskRef('task-3')
        ledger.acquire()
        ledger.assign(task)
        progress.onEvent(new EngineEvent.RunStarted('task-3', new Position.PipelineEnd(), 4))

        when:
        def entries = SlotEntryAssembler.assemble(ledger, progress)

        then:
        entries.size() == 1
        entries[0].stage() == null
        entries[0].attempt() == 4
    }

    def "multiple occupied slots each produce their own entry"() {
        given:
        def ledger = new SlotLedger(2, clock)
        def progress = new HeartbeatProgress()
        ledger.acquire()
        ledger.assign(new TaskRef('task-a'))
        ledger.acquire()
        ledger.assign(new TaskRef('task-b'))
        progress.onEvent(new EngineEvent.AttemptStarted(new AttemptKey('task-a', 'plan', 1)))
        progress.onEvent(new EngineEvent.AttemptStarted(new AttemptKey('task-b', 'review', 3)))

        when:
        def entries = SlotEntryAssembler.assemble(ledger, progress)

        then:
        entries.size() == 2
        entries*.taskId() as Set == ['task-a', 'task-b'] as Set
        entries.find { it.taskId() == 'task-a' }.stage() == 'plan'
        entries.find { it.taskId() == 'task-b' }.stage() == 'review'
    }
}
