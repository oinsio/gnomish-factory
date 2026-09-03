package com.github.oinsio.gnomish.serveobservability.writer

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link TaskOutcomeLedgerWriter}: the {@code TakeSlotRunner} write point (design D6, FR11) that
 * looks up a finishing slot's {@code startedAt} from {@link SlotLedger}, maps the terminal {@link
 * TakeResult} through {@code TaskOutcomeLineAssembler}, and appends it — only for the four
 * variants carrying a {@code finalState}; {@code EmptyQueue}/{@code Skipped} append nothing.
 *
 * <p>Implements FR11 of add-serve-observability.
 */
class TaskOutcomeLedgerWriterSpec extends Specification implements RotatingLedgerAppenderFixture {

    @TempDir
    Path homeDir

    private static final String INSTANCE_NAME = 'gnomish'
    private static final InstanceInfo INSTANCE = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')
    private static final ObjectMapper JSON = new ObjectMapper()

    private VirtualClock slotClock = new VirtualClock()
    private SlotLedger slotLedger = new SlotLedger(2, slotClock)

    private TaskOutcomeLedgerWriter writer(Instant now) {
        def appender = ledgerAppenderFor(homeDir, INSTANCE_NAME, now)
        return new TaskOutcomeLedgerWriter(slotLedger, appender, INSTANCE, Clock.fixed(now, ZoneOffset.UTC))
    }

    private Path ledgerFile(Instant now) {
        return ledgerFileFor(homeDir, INSTANCE_NAME, now)
    }

    private static TaskState finalState(Position position) {
        new TaskState(position, 1, [], ExecutorUsage.none())
    }

    def "writes exactly one taskOutcome line for a Delivered result, with startedAt from the slot ledger"() {
        given:
        def ref = new TaskRef('PROJ-1')
        slotClock.instant = Instant.parse('2026-08-03T10:00:00Z')
        slotLedger.assign(ref)
        def now = Instant.parse('2026-08-03T10:05:00Z')
        def result = new TakeResult.Delivered(finalState(new Position.AtStage('build')), 'shipped it')

        when:
        writer(now).write(ref, result)

        then:
        def lines = Files.readString(ledgerFile(now)).split('\n').findAll {
            !it.isBlank()
        }
        lines.size() == 1
        def json = JSON.readTree(lines[0])
        json.get('type').asText() == 'taskOutcome'
        json.get('taskId').asText() == 'PROJ-1'
        json.get('outcome').asText() == 'delivered'
        json.get('startedAt').asText() == '2026-08-03T10:00:00Z'
        json.get('finishedAt').asText() == '2026-08-03T10:05:00Z'
        json.get('wallMillis').asLong() == Duration.ofMinutes(5).toMillis()
    }

    def "writes an awaitingHuman line carrying its parkReason"() {
        given:
        def ref = new TaskRef('PROJ-2')
        slotClock.instant = Instant.parse('2026-08-03T10:00:00Z')
        slotLedger.assign(ref)
        def now = Instant.parse('2026-08-03T10:01:00Z')
        def result = new TakeResult.AwaitingHuman(
                finalState(new Position.AtStage('build')),
                ParkReason.ESCALATION,
                'needs a human')

        when:
        writer(now).write(ref, result)

        then:
        def json = JSON.readTree(Files.readString(ledgerFile(now)).trim())
        json.get('outcome').asText() == 'awaitingHuman'
        json.get('parkReason').asText() == 'escalation'
    }

    def "writes nothing to the ledger directory for EmptyQueue"() {
        given:
        def ref = new TaskRef('PROJ-3')
        slotClock.instant = Instant.parse('2026-08-03T10:00:00Z')
        slotLedger.assign(ref)
        def now = Instant.parse('2026-08-03T10:01:00Z')

        when:
        writer(now).write(ref, new TakeResult.EmptyQueue())

        then:
        !Files.exists(ledgerFile(now))
    }

    def "writes nothing to the ledger directory for Skipped"() {
        given:
        def ref = new TaskRef('PROJ-4')
        slotClock.instant = Instant.parse('2026-08-03T10:00:00Z')
        slotLedger.assign(ref)
        def now = Instant.parse('2026-08-03T10:01:00Z')

        when:
        writer(now).write(ref, new TakeResult.Skipped('lost claim race'))

        then:
        !Files.exists(ledgerFile(now))
    }

    // PIT: startedAtFor's filter must match the SPECIFIC claimed task, not just return the first
    // occupied entry — kills a "filter always true" mutant that collapses every lookup to the
    // first-iterated slot's since. Both tasks are finished and each line must carry ITS OWN since;
    // under the mutant both lines would share one since, so at least one mismatches — an assertion
    // that holds regardless of occupiedEntries() iteration order (looking up only one task would
    // pass whenever that task happened to iterate first, leaving the mutant flakily alive).
    def "matches each finishing task's own occupied-slot startedAt, not a shared first-entry one"() {
        given:
        def refA = new TaskRef('PROJ-A')
        def refB = new TaskRef('PROJ-B')
        slotClock.instant = Instant.parse('2026-08-03T09:00:00Z')
        slotLedger.assign(refA)
        slotClock.instant = Instant.parse('2026-08-03T09:30:00Z')
        slotLedger.assign(refB)
        def now = Instant.parse('2026-08-03T10:00:00Z')
        def result = new TakeResult.Delivered(finalState(new Position.AtStage('build')), 'shipped it')

        when: 'both occupied tasks finish, each looked up specifically'
        def w = writer(now)
        w.write(refA, result)
        w.write(refB, result)

        then: 'each line carries its own slot since (distinct), never a single shared first-entry since'
        def startedByTask = Files.readString(ledgerFile(now)).split('\n')
                .findAll { !it.isBlank() }
                .collect { JSON.readTree(it) }
                .collectEntries {
                    [(it.get('taskId').asText()): it.get('startedAt').asText()]
                }
        startedByTask['PROJ-A'] == '2026-08-03T09:00:00Z'
        startedByTask['PROJ-B'] == '2026-08-03T09:30:00Z'
    }

    // NFR-R1: a write failure (a blocked ledger directory) must never escape write() and crash
    // the slot that is finishing.
    //
    // FR15 of harden-logging-observability: the lost line is a task's own outcome, so the ERROR
    // carries the taskId — the attribution key an operator greps the run by.
    def "swallows an IOException from a blocked ledger directory, leaving an ERROR naming the task"() {
        given:
        def ref = new TaskRef('PROJ-6')
        slotClock.instant = Instant.parse('2026-08-03T10:00:00Z')
        slotLedger.assign(ref)
        def now = Instant.parse('2026-08-03T10:01:00Z')
        def result = new TakeResult.Delivered(finalState(new Position.AtStage('build')), 'shipped it')
        Files.writeString(homeDir.resolve('.gnomish'), 'not a directory')
        def logs = LogCaptureSupport.attach(TaskOutcomeLedgerWriter)

        when:
        writer(now).write(ref, result)

        then:
        noExceptionThrown()
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.TASK_OUTCOME_LEDGER_APPEND_FAILED.head())
        }
        event != null
        event.level == Level.ERROR
        event.formattedMessage.contains('PROJ-6')

        cleanup:
        logs.detach()
    }

    // FR15: the skip is a missing outcome line, so it is WARN and it names the task it skipped.
    def "never throws when no slot ledger entry exists for the task (defensive, should not happen in production)"() {
        given:
        def ref = new TaskRef('PROJ-5')
        def now = Instant.parse('2026-08-03T10:01:00Z')
        def result = new TakeResult.Delivered(finalState(new Position.AtStage('build')), 'shipped it')
        def logs = LogCaptureSupport.attach(TaskOutcomeLedgerWriter)

        when:
        writer(now).write(ref, result)

        then:
        noExceptionThrown()
        !Files.exists(ledgerFile(now))
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.TASK_OUTCOME_SLOT_MISSING.head())
        }
        event != null
        event.level == Level.WARN
        event.formattedMessage.contains('PROJ-5')

        cleanup:
        logs.detach()
    }
}
