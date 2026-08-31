package com.github.oinsio.gnomish.app.branch

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * NFR-O1 of harden-task-branch-contract: every non-trivial repair leaves one structured line naming
 * the shape, the task, the epoch and the action; a repair of a task whose accounting already records
 * one is raised to a warning.
 */
class BranchRepairLogSpec extends Specification {

    def repairLog = new BranchRepairLog()

    /** Runs {@code emit} with a {@link ListAppender} attached to the repair log's own logger. */
    private static List<ILoggingEvent> capture(Closure<?> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(BranchRepairLog)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    // NFR-O1: one line, naming shape, task, epoch, owner and action.
    def "a repair logs one line naming the shape, task, epoch and action"() {
        when:
        def events = capture {
            repairLog.classified('PROJ-1', new BranchShape.CompletedUncleaned(), new ClaimEpoch(42),
            'finishing cleanup', 0)
        }

        then:
        events.size() == 1
        events[0].level == Level.INFO
        def line = events[0].formattedMessage
        line.contains('task=PROJ-1')
        line.contains('shape=CompletedUncleaned')
        line.contains('epoch=42')
        line.contains('owner=COMPLETION_FINISH')
        line.contains('action=finishing cleanup')
    }

    // NFR-O1: a healthy pickup is not a repair — a line per pickup would bury the ones that matter.
    def "a clean shape logs nothing"() {
        expect:
        capture {
            repairLog.classified('PROJ-1', shape, new ClaimEpoch(1), 'resuming', 0)
        }.isEmpty()

        where:
        shape << [
            new BranchShape.Created(),
            new BranchShape.InProgress(),
            new BranchShape.Answered(),
            new BranchShape.Delivered()
        ]
    }

    // NFR-O1: "repeated" is judged against the task's persisted recovery accounting, not a clock.
    def "a repair with prior attempts recorded warns"() {
        when:
        def events = capture {
            repairLog.classified('PROJ-1', new BranchShape.Bare(), new ClaimEpoch(7), 'writing the STARTED commit', 2)
        }

        then:
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.contains('priorAttempts=2')
        events[0].formattedMessage.contains('(repeated)')
    }

    // NFR-O1: a reader holding no claim still logs its repair; the epoch simply has no value.
    def "a missing epoch renders as none"() {
        expect:
        capture {
            repairLog.classified('PROJ-1', new BranchShape.Parked(), null, 'completing the tracker write', 0)
        }
        .first().formattedMessage.contains('epoch=none')
    }

    // NFR-O2: the quarantining shapes carry their diagnosis into the line, so the operator reads
    // what was wrong without reopening the branch.
    def "a quarantining shape carries its diagnosis into the line"() {
        expect:
        capture {
            repairLog.classified('PROJ-1', shape, new ClaimEpoch(1), 'quarantining', 0)
        }
        .first().formattedMessage.contains(fragment)

        where:
        shape || fragment
        new BranchShape.Corrupt('task.json: truncated') || 'Corrupt(task.json: truncated)'
        new BranchShape.Unknown('state.json without task.json') || 'Unknown(state.json without task.json)'
        new BranchShape.UnsupportedVersion('state.json', 7, 1) || 'UnsupportedVersion(state.json: 7, supported 1)'
    }

    // NFR-O1: the stale-epoch discard is a repair like any other and names its own shape.
    def "a stale-epoch discard logs its shape"() {
        expect:
        capture {
            repairLog.classified('PROJ-1', new BranchShape.StaleEpoch(), new ClaimEpoch(9), 'discarding', 0)
        }
        .first().formattedMessage.contains('shape=StaleEpoch')
    }
}
