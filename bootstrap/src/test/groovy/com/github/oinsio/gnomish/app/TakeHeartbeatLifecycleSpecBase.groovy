package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The heartbeat-lifecycle proof for the {@code take} run (task 6.1 of add-claim-heartbeat, FR1):
 * once a claim is acquired the instance beats it on the configured interval WHILE a long round is in
 * flight, and beating STOPS at the terminal result. The "long round" is the {@code
 * plain-round-slow} fake-agent scenario, which stays in flight for a couple of seconds so the claim
 * is genuinely held while the beats land. The assembly is {@link TakeHeartbeatFixtureBase}'s.
 *
 * <p>Implements FR1 of add-claim-heartbeat.
 */
abstract class TakeHeartbeatLifecycleSpecBase extends TakeHeartbeatFixtureBase {

    // Scenario: tracker/tracker-take / Beat starts with the claim
    // Scenario: lifecycle/claim-heartbeat / Beats continue through a long round
    def "FR1: the held claim is beaten during a long round and beating stops at the terminal result"() {
        given: 'a Ready task and a fake-agent stage that stays in flight for a couple of seconds'
        def factoryProperties = FakeAgentSupport.propertiesFor('plain-round-slow')
        def command = newCommand(factoryProperties)
        // Built outside the closure below: the args helper is a private static method the closure
        // cannot resolve against the spec instance (Groovy dynamic dispatch), so capture it here.
        def appArgs = args('take', 'PROJ-1', "--dir=$projectDir")
        def executor = Executors.newSingleThreadExecutor()

        when: 'take runs the ready task on another thread so the test can drive beats mid-round'
        Future<?> run = executor.submit({ command.run(appArgs) } as Callable)

        and: 'the beat thread parks in its first interval sleep, then the test drives two beats'
        // Bounded waits (not the unbounded awaitEntered()) so a mutant that never starts the beat
        // thread — e.g. dropping heartbeat.register(ref) in TakeClaimAndWork#dispatchAfterClaim —
        // is KILLED by a fast assertion here rather than hanging this covering spec into a PIT
        // TIMED_OUT. The window is generous versus the near-instant real start; only a broken beat
        // lifecycle ever exhausts it.
        def firstInterval = sleeper.awaitEntered(10000)
        assert firstInterval != null: 'the beat thread never entered its first interval sleep'
        sleeper.releaseOne() // beat #1
        assert sleeper.awaitEntered(10000) != null: 'the beat thread stopped before beat #2'
        sleeper.releaseOne() // beat #2
        // the worker is now parked again, having beaten twice
        assert sleeper.awaitEntered(10000) != null: 'the beat thread stopped before parking after beat #2'

        then: 'the beat thread slept the configured default beat interval (FR1, config default)'
        firstInterval == TrackerConfig.DEFAULT_HEARTBEAT_INTERVAL

        and: 'the held claim was beaten twice while the round was still in flight (FR1)'
        int beatsDuringRound = heartbeatCount(tracker, REF)
        beatsDuringRound == 2

        when: 'the round completes to its terminal result'
        Throwable thrown = null
        try {
            run.get(30, TimeUnit.SECONDS)
        } catch (ExecutionException e) {
            thrown = e.cause
        }

        then: 'the run reached the Delivered exit code (0), and the task ended Finished'
        thrown instanceof TakeExitCodeException
        (thrown as TakeExitCodeException).exitCode() == 0
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        when: 'the parked beat thread is woken once more, now that the claim was unregistered'
        sleeper.releaseOne()

        then: 'it finds no held claim and stops without ever sleeping — or beating — again (FR1)'
        sleeper.awaitEntered(1000) == null
        heartbeatCount(tracker, REF) == beatsDuringRound

        cleanup:
        executor.shutdownNow()
    }
}
