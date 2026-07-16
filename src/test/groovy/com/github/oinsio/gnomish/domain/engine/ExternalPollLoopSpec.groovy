package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import java.time.Duration

/**
 * VerifyOrchestrator external poll loop, task 4.3 — an external check polls until it reaches
 * a verdict within its timeout, sleeping exactly the interval between polls (FR3); an
 * unresolved Running times out at the deadline into a quality Fail (FR3, NFR-R3); a Fail or
 * CannotVerify on the first poll maps straight through without sleeping. Implements FR3,
 * NFR-R3 of add-stage-engine.
 */
class ExternalPollLoopSpec extends VerifyOrchestratorSpecBase {

    // FR3: an external check that reaches a verdict within its timeout passes; the loop
    //      polls once per Running, sleeping exactly the interval between polls
    def "polls an external check to a Pass within the timeout, sleeping the interval between polls"() {
        given: 'a poller that is Running twice then Pass, on a 1s/3s check'
        def interval = Duration.ofSeconds(1)
        def timeout = Duration.ofSeconds(3)
        def externalClient = new ScriptedExternalCheckClient([
            new PollStatus.Running(),
            new PollStatus.Running(),
            new PollStatus.Pass()
        ])
        def check = external('ci/build', interval, timeout)

        when: 'the single external check is verified'
        def result = orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(), externalClient)
                .verify([check], CONTEXT, WORKSPACE, KEY)

        then: 'the check passed'
        result.results.size() == 1
        result.results[0].verdict instanceof Verdict.Pass

        and: 'the client was polled three times, once per status'
        externalClient.pollCount == 3

        and: 'the loop slept exactly twice, each of the interval — so virtual time advanced 2*interval'
        sleeper.slept == [interval, interval]
        clock.now() == java.time.Instant.EPOCH.plus(interval.multipliedBy(2))
    }

    // FR3, NFR-R3: a Running that never resolves times out at the deadline into a quality
    //      Fail carrying a single finding naming the check id and timeout — and stops
    def "times an unresolved external check out into a quality Fail once the deadline elapses"() {
        given: 'a poller that stays Running past the deadline, on a 1s/3s check'
        def interval = Duration.ofSeconds(1)
        def timeout = Duration.ofSeconds(3)
        // deadline is EPOCH+3s: polls at t=0,1,2 sleep; the t=3 poll is at the deadline and fails
        def externalClient = new ScriptedExternalCheckClient([
            new PollStatus.Running(),
            new PollStatus.Running(),
            new PollStatus.Running(),
            new PollStatus.Running()
        ])
        def check = external('ci/slow', interval, timeout)

        when: 'the single external check is verified'
        def result = orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(), externalClient)
                .verify([check], CONTEXT, WORKSPACE, KEY)

        then: 'the check timed out into a quality Fail with a single timeout finding'
        result.results.size() == 1
        def verdict = result.results[0].verdict
        verdict instanceof Verdict.Fail
        verdict.findings().size() == 1
        verdict.findings()[0].message().contains('ci/slow')
        verdict.findings()[0].message().contains(timeout.toString())

        and: 'the loop stopped rather than polling forever, having slept up to the timeout'
        externalClient.pollCount == 4
        sleeper.slept.size() == 3
        sleeper.slept.inject(Duration.ZERO) { acc, d -> acc.plus(d) } >= timeout
    }

    // FR3: a Fail on the first poll maps straight through to a quality Verdict.Fail — no sleep
    def "maps an external Fail straight through to a quality Fail without sleeping"() {
        given: 'a poller that fails immediately with findings'
        def findings = [
            new Finding('gate red', null, null)
        ]
        def externalClient = new ScriptedExternalCheckClient([new PollStatus.Fail(findings)])
        def check = external('ci/gate', Duration.ofSeconds(1), Duration.ofSeconds(3))

        when: 'the single external check is verified'
        def result = orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(), externalClient)
                .verify([check], CONTEXT, WORKSPACE, KEY)

        then: 'the verdict is a Fail carrying the poll findings, reached in one poll with no sleep'
        result.results[0].verdict instanceof Verdict.Fail
        result.results[0].verdict.findings() == findings
        externalClient.pollCount == 1
        sleeper.slept.isEmpty()
    }

    // FR3: a CannotVerify on the first poll maps straight through to Verdict.CannotVerify — no sleep
    def "maps an external CannotVerify straight through to CannotVerify without sleeping"() {
        given: 'a poller that cannot verify immediately'
        def externalClient = new ScriptedExternalCheckClient([
            new PollStatus.CannotVerify('check id unknown', 'no such check')
        ])
        def check = external('ci/missing', Duration.ofSeconds(1), Duration.ofSeconds(3))

        when: 'the single external check is verified'
        def result = orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(), externalClient)
                .verify([check], CONTEXT, WORKSPACE, KEY)

        then: 'the verdict is a CannotVerify with the same reason and details, reached without sleeping'
        result.results[0].verdict instanceof Verdict.CannotVerify
        result.results[0].verdict.reason() == 'check id unknown'
        result.results[0].verdict.details() == 'no such check'
        externalClient.pollCount == 1
        sleeper.slept.isEmpty()
    }
}
