package com.github.oinsio.gnomish.adapter.agent.program

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.app.port.check.PendingAdoption
import com.github.oinsio.gnomish.executorcontract.Denial
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * {@link GuardedExecutor}, the one owner of the egress guard's probe (FR15, design D13 of
 * extract-agent-round-runner): the environment's guard is probed before and after every {@code start}
 * and {@code poll}; a probe that throws before the request issues none and answers {@code
 * failed/infrastructure} naming the guard, one that throws after it replaces the executor's answer,
 * its usage and denials kept; a live guard leaves the answer untouched; the adoption role is not the
 * decorator's to take.
 */
class GuardedExecutorSpec extends Specification {

    static final PendingKey KEY = new PendingKey('k-1')

    def request = ProgramFixtures.request()

    def denial = new Denial(UntrustedText.subprocess('egress denied: evil.example'), null, null)

    def usage = Usage.absent()

    def answered = new ExecutorResult(Status.COMPLETED, null, null, null,
    JsonNodeFactory.instance.objectNode().put('kind', 'verdict'), usage, [denial])

    def environment = Mock(TaskExecutionEnvironment)

    def delegate = Mock(Executor)

    private GuardedExecutor guarded(Executor executor = delegate) {
        GuardedExecutor.over(ProgramFailures.Subject.PROGRAM, 'judge-bot', environment, executor)
    }

    private static RuntimeException dead() {
        new IllegalStateException('egress guard gnomish-guard-7 is not running (exited)\u001b[31m')
    }

    def "FR15: a live guard is probed before and after the #operation, and the answer is the executor's"() {
        given:
        def executor = guarded()

        when:
        def result = call(executor, request)

        then:
        1 * environment.probeGuard()

        then:
        1 * delegate./start|poll/(*_) >> answered

        then:
        1 * environment.probeGuard()
        result.is(answered)
        !executor.foundGuardDead()

        where:
        operation | call
        'start' | { GuardedExecutor e, ExecutorRequest r -> e.start(r) }
        'poll' | { GuardedExecutor e, ExecutorRequest r -> e.poll(r, KEY) }
    }

    // Scenario: sandbox/sandbox-egress / A dead guard around a check in a box is cannot-verify
    def "FR15: a guard dead before the #operation issues no request and is failed/infrastructure naming the guard"() {
        given:
        def executor = guarded()

        when:
        def result = call(executor, request)

        then:
        1 * environment.probeGuard() >> { throw dead() }
        0 * delegate._
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().forLog().startsWith(
                "program executor 'judge-bot': the egress guard of its environment was not running before the request: ")
        result.reason().contains('egress guard gnomish-guard-7 is not running (exited)')
        !result.reason().contains('\u001b')
        result.denials().isEmpty()
        executor.foundGuardDead()

        where:
        operation | call
        'start' | { GuardedExecutor e, ExecutorRequest r -> e.start(r) }
        'poll' | { GuardedExecutor e, ExecutorRequest r -> e.poll(r, KEY) }
    }

    def "FR15: a guard dead after the #operation replaces the executor's answer, its usage and denials kept"() {
        given:
        def executor = guarded()

        when:
        def result = call(executor, request)

        then:
        1 * environment.probeGuard()

        then:
        1 * delegate./start|poll/(*_) >> answered

        then:
        1 * environment.probeGuard() >> { throw dead() }
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.answer() == null
        result.reason().forLog().startsWith(
                "program executor 'judge-bot': the egress guard of its environment was not running after the request: ")
        result.reason().contains('gnomish-guard-7')
        result.usage().is(usage)
        result.denials() == [denial]
        executor.foundGuardDead()

        where:
        operation | call
        'start' | { GuardedExecutor e, ExecutorRequest r -> e.start(r) }
        'poll' | { GuardedExecutor e, ExecutorRequest r -> e.poll(r, KEY) }
    }

    def "FR15: a probe that throws with no message names the guard all the same"() {
        given:
        environment.probeGuard() >> { throw new IllegalStateException() }

        when:
        def result = guarded().start(request)

        then:
        result.reason().forLog().endsWith('was not running before the request: null')
    }

    def "the describe is the executor's"() {
        given:
        def describe = ProgramFixtures.describe()
        delegate.describe() >> describe

        expect:
        guarded().describe().is(describe)
    }

    // D14 of migrate-verify-to-executor-contract: the check round is the one caller of an adoption, so
    // the decorator neither takes nor forwards the role; the check binding hands it beside the decorator
    def "FR15: the decorator takes no adoption role, even over an executor that does"() {
        expect:
        !(guarded(Mock(AdoptingExecutor)) instanceof PendingAdoption)
        !(guarded() instanceof PendingAdoption)
    }

    /** An executor in the adoption role, as the program adapter is. */
    interface AdoptingExecutor extends Executor, PendingAdoption {}

    /** The message is untrusted container text: a reason built from it carries its excerpt. */
    def "the quoted message is cut to the excerpt bound"() {
        given:
        environment.probeGuard() >> {
            throw new IllegalStateException('x' * 5000)
        }

        when:
        def reason = guarded().start(request).reason()

        then:
        reason.length() < 1000
        UntrustedText.container('x' * 5000).excerpt(GuardedExecutor.MESSAGE_CHARS).with {
            reason.contains(it)
        }
    }
}
