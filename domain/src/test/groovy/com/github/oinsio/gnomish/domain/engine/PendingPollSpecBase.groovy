package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * Shared fixture of the {@link PendingPoll} specs (design D2, D19, D21 of
 * migrate-verify-to-executor-contract): the poll over fake ports on virtual time — a scripted {@code
 * StageExecutor} answering the check rounds, the in-memory persistence recording each pending write,
 * a virtual clock advanced only by the virtual sleeper — and builders for the round, the check and
 * the answers.
 */
abstract class PendingPollSpecBase extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def KEY = new AttemptKey('TASK-1', 'build', 0)
    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    static final def STATE = TaskState.atStageStart('build')
    static final def TOKEN = RoundToken.of('a' * 40)
    static final def OTHER_TOKEN = RoundToken.of('b' * 40)
    static final def TRACE = new ToolTrace(KEY, [])
    static final Duration INTERVAL = Duration.ofSeconds(30)
    static final Duration TIMEOUT = Duration.ofMinutes(10)

    def clock = new VirtualClock()
    def sleeper = new BudgetedVirtualSleeper(clock, 200)
    def persistence = new InMemoryAttemptPersistence()
    def events = new RecordingEventListener()

    static VerifyCheck.Executor check(String id = 'ci', VerifyCheck.TimeoutClass timeoutClass = VerifyCheck.TimeoutClass.QUALITY,
            Duration interval = INTERVAL, Duration timeout = TIMEOUT) {
        VerifyChecks.external(id, 'scm-status', interval, timeout, timeoutClass)
    }

    static CheckRef ref(int index, VerifyCheck check) {
        CheckRef.of(index, check)
    }

    static PendingRound round(List<VerifyCheck> checks, TaskState state = STATE, RoundToken token = TOKEN,
            AttemptKey key = KEY) {
        new PendingRound(VerifyingStages.of(checks), CONTEXT, WORKSPACE, key, state, token)
    }

    static ExecutionResult pending(String key, Duration hint = null, PendingState state = PendingState.RUNNING,
            String reason = null) {
        result(new ExecutionResult.Pending(new PendingKey(key), hint, state,
                reason == null ? null : UntrustedText.manifest(reason), null))
    }

    static ExecutionResult passed() {
        new ExecutionResult(new ExecutionResult.Completed(), new ExecutionResult.VerdictReply(new VerdictAnswer.Sarif([], null)),
        ExecutorUsage.none(), TRACE, [])
    }

    static ExecutionResult result(ExecutionResult.Status status) {
        new ExecutionResult(status, null, ExecutorUsage.none(), TRACE, [])
    }

    static PendingPollRecord entry(CheckRef check, String key, Instant since, RoundToken token = TOKEN, int attempt = 0) {
        new PendingPollRecord(new PendingSubject.Check(check), attempt, token, new PendingKey(key), since)
    }

    PendingPoll poll(ScriptedExecutor executor, AttemptDelivery delivery = AttemptDelivery.assumedDelivered()) {
        new PendingPoll(executor, persistence, delivery, VirtualTimeEquipment.on(clock, sleeper), events)
    }

    /** The verify chain with {@code poll} as the owner of its executor checks' rounds. */
    VerifyOrchestrator orchestrator(PendingPoll poll) {
        new VerifyOrchestrator(poll, clock, new RecordingEventListener())
    }

    /** The turns the executor was asked with, in order: null for a start, else origin and key. */
    static List<List<Object>> turns(ScriptedExecutor executor) {
        executor.turns.collect {
            it == null ? null : [
                it.origin(),
                it.key().value()
            ]
        }
    }

    static final PendingTurn.Origin ISSUED = PendingTurn.Origin.ISSUED
    static final PendingTurn.Origin RECORDED = PendingTurn.Origin.RECORDED
}
