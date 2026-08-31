package com.github.oinsio.gnomish.app.take

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.branch.BranchRecoveryFailedException
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * TakeCrashAbort: the crash arm of the infrastructure-abort protocol (task 5.3) — an uncaught
 * RuntimeException of a post-claim take run is funneled into the same best-effort AbortHandler
 * call an engine Aborted outcome makes, so the claim is released (recordAbort, Working -> Ready)
 * or parked at the K fuse and the run exits 12 or 13, never a bare 1. Covers the below-fuse and
 * fuse-trip branches and the best-effort abort-facts read that tolerates a dead tracker.
 *
 * FR14, NFR-R2, D3, D16 of add-tracker-port.
 */
class TakeCrashAbortSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    private static final Clock CLOCK = Clock.fixed(Instant.parse('2026-07-24T10:00:00Z'), ZoneOffset.UTC)
    private static final int THRESHOLD = 3

    private Tracker tracker = Mock()
    private AbortHandler abortHandler = new AbortHandler(tracker, CLOCK)
    private TakeCrashAbort crashAbort = new TakeCrashAbort(abortHandler, THRESHOLD)

    private static PipelineDefinition pipeline() {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    private static TrackerTask claimedTask(AbortFacts facts) {
        new TrackerTask(
                REF, new TaskSnapshot('PROJ-1', 'title', 'body'),
                new TrackerTaskState.Working(INSTANCE.value()), facts, false)
    }

    // FR14 "Runner crash is an abort", D16 "never a bare 1": below the fuse, a crash records the
    // abort (Working -> Ready) and returns Aborted carrying the crash's type/message as the cause;
    // the reported final state is the pipeline's first stage (a crash has no live engine state).
    def "a crash below the fuse records the abort and returns Aborted carrying the crash cause"() {
        given: 'the tracker reports no prior aborts for the claimed task'
        tracker.fetchTask(REF) >> claimedTask(AbortFacts.none())

        when:
        def result = crashAbort.onCrash(pipeline(), claimedTask(AbortFacts.none()), tracker, INSTANCE,
                new IllegalStateException('git worktree add exploded'))

        then: 'the abort is recorded, the fuse never parks, and the result is Aborted'
        1 * tracker.recordAbort(REF, _)
        0 * tracker.park(*_)
        result instanceof TakeResult.Aborted
        def aborted = result as TakeResult.Aborted
        aborted.cause().contains('uncaught exception during the take run')
        aborted.cause().contains('git worktree add exploded')
        aborted.finalState() == TaskState.atStageStart('build')
    }

    // FR14, NFR-C1: a crash whose prior abort count reaches the threshold once incremented trips
    // the fuse — park(INFRA) instead of recordAbort — exiting 13 rather than 12.
    def "a crash that reaches the threshold trips the fuse and parks INFRA"() {
        given: 'a prior abort count one below the threshold'
        def facts = new AbortFacts(THRESHOLD - 1, Instant.parse('2026-07-24T09:00:00Z'))
        tracker.fetchTask(REF) >> claimedTask(facts)

        when:
        def result = crashAbort.onCrash(pipeline(), claimedTask(facts), tracker, INSTANCE,
                new RuntimeException('salvage push failed'))

        then:
        0 * tracker.recordAbort(*_)
        1 * tracker.park(REF, ParkReason.INFRA, { String report ->
            report.contains('salvage push failed')
        })
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.INFRA
    }

    // FR14 of harden-task-branch-contract: the two categories of the unified accounting are
    // separated at the crash boundary — a failed branch repair names itself on the way up, even
    // wrapped by a layer above it, and everything else is an instance crash
    def "a crash carrying a failed branch repair spends the recovery category (#description)"() {
        given:
        tracker.fetchTask(REF) >> claimedTask(AbortFacts.none())
        def repairFailure = new BranchRecoveryFailedException(
                'PROJ-1', new BranchShape.Parked(), new IllegalStateException('park write failed'))

        when:
        crashAbort.onCrash(pipeline(), claimedTask(AbortFacts.none()), tracker, INSTANCE, crash(repairFailure))

        then:
        1 * tracker.recordAbort(REF, { it.category() == expected })

        where:
        description | crash | expected
        'thrown directly' | { Throwable cause ->
            cause
        } | RecoveryCause.RECOVERY_FAILURE
        'wrapped by a layer' | { Throwable cause ->
            new RuntimeException('while resuming', cause)
        } | RecoveryCause.RECOVERY_FAILURE
        'an ordinary crash' | { Throwable cause ->
            new IllegalStateException('worktree exploded')
        } | RecoveryCause.INSTANCE_CRASH
    }

    // NFR-R2: a dead tracker is itself a plausible crash cause and must never turn the abort into a
    // bare 1 — an abort-facts read that throws is treated as none(), so the crash still aborts.
    def "a crash whose abort-facts read fails is treated as none and still aborts"() {
        given: 'the abort-facts read itself throws, as a fully dead tracker would'
        tracker.fetchTask(REF) >> {
            throw new RuntimeException('tracker unreachable')
        }

        when:
        def result = null
        def events = capture {
            result = crashAbort.onCrash(pipeline(), claimedTask(AbortFacts.none()), tracker, INSTANCE,
            new RuntimeException('boom'))
        }

        then: 'with no facts to read the fuse counts the first abort and records it'
        noExceptionThrown()
        1 * tracker.recordAbort(REF, _)
        0 * tracker.park(*_)
        result instanceof TakeResult.Aborted

        and: 'the degrade is not silent: the reset streak is named at WARN with the failure attached'
        def warn = events.find { it.level == Level.WARN }
        warn != null
        warn.formattedMessage.contains('PROJ-1')
        warn.formattedMessage.contains('first abort in the streak')
        warn.throwableProxy.message == 'tracker unreachable'
    }

    /** Runs {@code emit} with a {@link ListAppender} attached to TakeCrashAbort's own logger. */
    private static List<ILoggingEvent> capture(Closure<?> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(TakeCrashAbort)
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
}
