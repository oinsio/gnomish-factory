package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages

/**
 * FR2, FR20 of migrate-verify-to-executor-contract (design D21, crash-consistency item 14): {@link
 * RoundExecution} hands the verify chain the state the round ran from and the token it took once for
 * the round, so a pending entry carries that token and its write carries that state — the entry
 * names its round by the identity the orchestrator minted, never by a second read.
 */
class RoundExecutionPendingHandoffSpec extends PendingPollSpecBase {

    def "FR20: the round's own token and state reach the pending write through the verify chain"() {
        given: 'one port serving the stage round, then the check round answering pending'
        def ci = check()
        def executor = new ScriptedExecutor([
            ExecutionResult.completed(ExecutorUsage.none(), TRACE, []),
            pending('k-1'),
            passed()
        ])
        def time = VirtualTimeEquipment.on(clock, sleeper)
        def ports = new EnginePorts(executor, new ScriptedJudgeVoter(), new RecordingEventListener(), persistence, time,
                { Optional.of(TOKEN) } as RoundIdentity)
        def listener = new RecordingEventListener()
        def chain = new VerifyOrchestrator(new PendingPoll(executor, persistence, AttemptDelivery.assumedDelivered(), time,
                listener), new JudgeVoting(new ScriptedJudgeVoter()), clock, listener)

        when:
        def outcome = new RoundExecution(ports, chain).execute(CONTEXT, STATE, WORKSPACE, VerifyingStages.of([ci]), clock.instant())

        then:
        persistence.pending*.state == [
            STATE.withPendingPolls([
                entry(ref(0, ci), 'k-1', clock.instant().minus(INTERVAL))
            ])
        ]
        (outcome as RoundOutcome.Judged).verdict() instanceof Verdict.Pass
        turns(executor) == [null, null, [ISSUED, 'k-1']]
    }
}
