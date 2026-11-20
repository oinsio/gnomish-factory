package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.adapter.git.AdversarialLocalConfig
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files

/**
 * The poll wire form of {@link ProgramExecutor} on the real host path (FR23, FR11, NFR-S4 of
 * define-executor-contract; design D3, D6), over the stand-in reference executor in a real git
 * working copy ({@link ProgramPollSpecBase}): the run's table of pending runs is shared by every
 * binding of the program and keyed per check (FR3 of migrate-verify-to-executor-contract; design D14
 * as amended 2026-11-13). A key adopted from the pending record by a new run is {@link
 * ProgramPollAdoptionSpec}'s, its identity rule {@link ProgramAdoptionIdentitySpec}'s. No feature rests
 * on a scheduling order — each run is waited to its end before the next begins.
 */
class ProgramPollSpec extends ProgramPollSpecBase {

    // Scenario: executor/wire-contract / Poll is addressed like start
    // FR23: the poll is the declared argv with `poll` appended, the same request on stdin and the key in GNOMISH_PENDING_KEY
    def "FR23: a #role run that returned pending is polled like it was started, and the poll's result is read from its result file"() {
        given:
        def executor = executor()
        def request = deferred(role)

        when:
        def started = executor.start(request)
        def polled = executor.poll(request, started.pendingKey())

        then: 'the reference answered the poll only because the word, the request and the key all reached it'
        started.status() == Status.PENDING
        started.pendingKey() == new PendingKey('reference-deferred')
        polled.status() == Status.COMPLETED
        polled.answer()?.path('kind')?.asText() == kind
        launches.get() == spawns

        and: 'the poll that ended the run left its key naming it: a re-poll reaches the program and is answered alike (D11)'
        def again = executor.poll(request, started.pendingKey())
        again.status() == Status.COMPLETED
        again.answer() == polled.answer()
        launches.get() == spawns + spawns.intdiv(2)

        and: 'the view of the working copy ran no program the working copy configuration planted'
        AdversarialLocalConfig.assertNothingRan(repo)

        where: 'a verdict run takes git view before its start and at its ending poll, a transform none'
        role || kind | spawns
        Role.TRANSFORM || null | 2
        Role.VERDICT || 'verdict' | 4
    }

    // Scenario: executor/wire-contract / Poll of a program without the capability
    // FR23: a program whose describe declares no poll is refused at the poll, with no process
    def "FR23: polling a program whose describe lists no poll capability is failed/infrastructure naming it, and spawns nothing"() {
        given:
        def executor = executor([])
        def started = executor.start(deferred(Role.TRANSFORM))
        int before = launches.get()

        when:
        def result = executor.poll(deferred(Role.TRANSFORM), started.pendingKey())

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains("program executor 'acme': its describe declares no poll capability")
        launches.get() == before
    }

    // Scenario: executor/wire-contract / Poll with a key this run did not issue
    // FR23, NG7: a key no start or poll of this run returned is refused, with no process — another run's included
    def "FR23: a poll with a key this run did not receive is failed/infrastructure naming the key, and spawns nothing"() {
        given: 'another run issued the key'
        def issued = executor([Capability.POLL], environment, PendingScope.unscoped(new PendingRuns()))
        .start(deferred(Role.TRANSFORM)).pendingKey()
        int before = launches.get()

        when:
        def result = executor().poll(deferred(Role.TRANSFORM), issued)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains("the pending key 'reference-deferred' is unknown: no start or poll of this program "
                + 'in this run returned it for this check, and the run adopted no such recorded key')
        launches.get() == before
    }

    // Scenario: executor/wire-contract / A key returned to an earlier binding is polled
    // FR3, D14 as amended 2026-11-13: the run's table is the entry's, shared by every binding the factory makes
    def "FR3: a key one binding's start returned is polled by a new binding of the same run, adopting nothing"() {
        given: 'one table for the program in the run, as the registry builds it beside the entry'
        def factory = new ProgramExecutorFactory(com.github.oinsio.gnomish.adapter.law.PipelineLaw.ofContent([:]),
        new DescribedProgram(ProgramFixtures.declaration([
            StandIn.executor('reference').toString()
        ]),
        ProgramFixtures.describe([Capability.POLL]), []), ProgramFixtures.equipment(stderrParent), runs)
        def request = deferred(Role.VERDICT)
        def started = factory.executor(environment).start(request)
        int before = launches.get()

        when:
        def polled = factory.executor(environment).poll(request, started.pendingKey())

        then: 'the poll and the judging view only: no view of an adoption'
        polled.status() == Status.COMPLETED
        polled.answer()?.path('kind')?.asText() == 'verdict'
        launches.get() == before + 2
    }

    // Scenario: executor/wire-contract / A key issued for one check is unknown to another
    // FR3, D14 as amended 2026-11-13: the table is keyed by the check's identity in the round and the key
    def "FR3: a key one check's binding received is unknown to another check's binding of the same table"() {
        given:
        def first = new CheckRef(0, UntrustedText.manifest('executor:acme:first'))
        def second = new CheckRef(1, UntrustedText.manifest('executor:acme:second'))
        def issued = executor([Capability.POLL], environment, new PendingScope(runs, first, null))
        .start(deferred(Role.VERDICT)).pendingKey()
        int before = launches.get()

        when:
        def result = executor([Capability.POLL], environment, new PendingScope(runs, second, null))
        .poll(deferred(Role.VERDICT), issued)

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains("the pending key 'reference-deferred' is unknown")
        launches.get() == before

        and: 'the first check still polls its own key'
        executor([Capability.POLL], environment, new PendingScope(runs, first, null))
        .poll(deferred(Role.VERDICT), issued).status() == Status.COMPLETED
    }

    // Scenario: executor/wire-contract / Pending judge is judged when it ends
    // FR23, FR11, NFR-S4: a file changed between a pending verdict's start and its ending poll refuses the verdict
    def "NFR-S4: a pending verdict run whose working copy changed after its start is refused at the poll that ends it, naming the path"() {
        given:
        def executor = executor()
        def request = deferred(Role.VERDICT)
        def started = executor.start(request)

        when: 'the judge, still running, writes into the working copy'
        Files.writeString(repo.resolve('tracked.txt'), 'the judge was here\n', java.nio.file.StandardOpenOption.APPEND)
        def polled = executor.poll(request, started.pendingKey())

        then: 'the pending answer was passed on unjudged; the ending one is discarded'
        started.status() == Status.PENDING
        polled.status() == Status.FAILED
        polled.failureClass() == FailureClass.INFRASTRUCTURE
        polled.answer() == null
        polled.reason().contains("program executor 'acme': a verdict run must leave the working copy unchanged "
                + '(policy readOnly); the run changed tracked.txt')
    }
}
