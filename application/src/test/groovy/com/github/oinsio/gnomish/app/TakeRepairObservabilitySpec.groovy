package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.branch.BranchRepairLog
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * NFR-O1 of harden-task-branch-contract, at the routing point rather than in isolation: a pickup
 * that classifies to anything other than a clean shape leaves exactly one repair line naming the
 * task, the shape, the tenure's claim epoch and the action — and a repair of a task whose persisted
 * accounting already records one is raised to a warning. {@code BranchRepairLogSpec} pins the line's
 * own shape; this spec pins that production routing actually emits it.
 */
class TakeRepairObservabilitySpec extends Specification implements RunChainFakes {

    /** A tenure record holding epoch 42 on the task under test, as a live claim would have left it. */
    private static ClaimEpochBook epoch42() {
        def book = new ClaimEpochBook()
        book.issued('PROJ-1', new ClaimEpoch(42))
        book
    }

    /** Runs {@code drive} with a {@link ListAppender} attached to the repair log's own logger. */
    private static List<ILoggingEvent> capture(Closure<?> drive) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(BranchRepairLog)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            drive()
        } catch (RuntimeException ignored) {
            // Every scenario stops the routed-to path at its first port call; the log is the subject.
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    /** The task as the tracker reports it, carrying {@code facts} as its recovery accounting. */
    private static TrackerTask taskWith(AbortFacts facts) {
        new TrackerTask(REF, new TaskSnapshot('PROJ-1', 'title', 'body'), new TrackerTaskState.Ready(), facts, false)
    }

    /** Task git whose branch exists and classifies to {@code shape}. */
    private TaskGit gitClassifying(BranchShape shape, TaskStoreGit store) {
        def branches = Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
            classifyShape(_, _) >> shape
        }
        new TaskGit(store, branches, Stub(TaskWorktreeGit))
    }

    /** Drives one claimed pickup of a branch classifying to {@code shape}, capturing its repair log. */
    private List<ILoggingEvent> pickup(Tracker tracker, BranchShape shape, AbortFacts facts, TaskStoreGit store) {
        def subject = claimAndWork(gitClassifying(shape, store), tracker, Stub(RunAssembly), ClaimBeat.NONE,
                new ClaimLossFlag(), WORKTREES_ROOT, epoch42())
        return capture {
            subject.claimAndWork(CLONE_DIR, null, pipeline(), RunArguments.InteractiveMode.NONE, false,
            taskWith(facts), tracker, INSTANCE)
        }
    }

    /** The resume bootstrap's last step, throwing so each scenario stops right after the routing. */
    private TaskStoreGit stoppingStore() {
        Stub(TaskStoreGit) {
            readTaskRecord(_) >> {
                throw new UsageException('stopped at the resume bootstrap')
            }
        }
    }

    private Tracker claimingTracker() {
        Stub(Tracker) {
            claim(_, _) >> new ClaimResult.Acquired(new ClaimEpoch(42))
        }
    }

    // NFR-O1: one line per non-clean pickup, naming shape, task, epoch and action.
    def "a non-clean pickup leaves one repair line naming shape, task, epoch and action"() {
        when:
        def events = pickup(claimingTracker(), new BranchShape.CompletedUncleaned(), AbortFacts.none(),
                stoppingStore())

        then:
        events.size() == 1
        events[0].level == Level.INFO
        def line = events[0].formattedMessage
        line.contains('task=PROJ-1')
        line.contains('shape=CompletedUncleaned')
        line.contains('epoch=42')
        line.contains('owner=COMPLETION_FINISH')
        line.contains('action=')
    }

    // NFR-O1: a healthy pickup is not a repair — no line, so the ones that matter stay readable.
    def "a clean pickup leaves no repair line"() {
        expect:
        pickup(claimingTracker(), shape, AbortFacts.none(), stoppingStore()).isEmpty()

        where:
        shape << [
            new BranchShape.InProgress(),
            new BranchShape.Created(),
            new BranchShape.Answered()
        ]
    }

    // NFR-O1: "repeated" is judged against the task's persisted recovery accounting (FR14).
    def "a repair of a task whose accounting already records one warns"() {
        when:
        def events = pickup(claimingTracker(), new BranchShape.CompletedUncleaned(),
                new AbortFacts(3, Instant.EPOCH, 2), stoppingStore())

        then:
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.contains('priorAttempts=2')
        events[0].formattedMessage.contains('(repeated)')
    }

    // NFR-O1, NFR-O2: a quarantining pickup is a repair too — its line carries the diagnosis, and
    // the park itself still happens (FR15).
    def "a quarantining pickup leaves its diagnosis in the repair line"() {
        given:
        def tracker = Mock(Tracker)
        tracker.claim(_, _) >> new ClaimResult.Acquired(new ClaimEpoch(42))

        when:
        def events = pickup(tracker, new BranchShape.Corrupt('task.json: bad json'), AbortFacts.none(),
                Stub(TaskStoreGit))

        then:
        events.size() == 1
        events[0].formattedMessage.contains('Corrupt(task.json: bad json)')

        and: 'the quarantine park still runs'
        1 * tracker.park(REF, ParkReason.INFRA, _)
    }
}
