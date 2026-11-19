package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.pendingkey.PendingKey
import java.nio.file.Files

/**
 * {@link ProgramExecutor#adopt} (FR3, FR11 of migrate-verify-to-executor-contract; design D14 as
 * amended 2026-11-13), on the real host path of {@link ProgramPollSpecBase}: a recorded key adopted by
 * a new run over a working copy on the expected {@code HEAD} and polled, judged against the view taken
 * at adoption, or refused over a changed working copy; and the edges — what is adopted, over which
 * view, and what a key this run already holds keeps. The identity rule over a round's registered
 * chain is {@link ProgramAdoptionIdentitySpec}'s.
 */
class ProgramPollAdoptionSpec extends ProgramPollSpecBase {

    // FR3, D14: a program that declares no poll adopts nothing; its poll refuses naming the capability
    def "FR3: adopting a key for a program whose describe lists no poll takes no view, and its poll refuses naming the capability"() {
        given:
        def executor = executor([])
        int before = launches.get()

        when:
        executor.adopt(deferred(Role.VERDICT), new PendingKey('reference-deferred'))
        def polled = executor.poll(deferred(Role.VERDICT), new PendingKey('reference-deferred'))

        then:
        polled.failureClass() == FailureClass.INFRASTRUCTURE
        polled.reason().contains('its describe declares no poll capability')
        launches.get() == before
    }

    // FR3, D14: a key the run's own start returned keeps the view of that start
    def "FR3: adopting a key this run's own start returned keeps the run it started, judged against its start's view"() {
        given:
        def executor = executor()
        def request = deferred(Role.VERDICT)
        def key = executor.start(request).pendingKey()
        Files.writeString(repo.resolve('leftover.txt'), 'written after the start\n')

        when:
        executor.adopt(request, key)
        def polled = executor.poll(request, key)

        then: 'the answer is judged against the start, not refused as an adoption'
        polled.failureClass() == FailureClass.INFRASTRUCTURE
        polled.reason().contains('the run changed leftover.txt')
        !polled.reason().contains('cannot be adopted')
    }

    // FR3, FR11, D14: a view that cannot be taken refuses the adoption, and the program is not spawned
    def "FR11: a recorded key adopted where git's view cannot be taken is refused saying so, spawning no program"() {
        given: 'a working copy that is no git repository'
        def bare = Files.createDirectory(tempDir.resolve('not-a-repo'))
        def elsewhere = environmentAt(bare)
        def executor = executor([
            com.github.oinsio.gnomish.executorcontract.Capability.POLL
        ], elsewhere)
        int before = launches.get()

        when:
        executor.adopt(deferred(Role.VERDICT), new PendingKey('reference-deferred'))
        def polled = executor.poll(deferred(Role.VERDICT), new PendingKey('reference-deferred'))

        then:
        polled.failureClass() == FailureClass.INFRASTRUCTURE
        polled.reason().contains("the pending key 'reference-deferred' recorded for this check cannot be adopted: "
                + "the working copy's state could not be taken: git status exited")
        launches.get() == before + 1

        cleanup:
        elsewhere.dispose()
    }

    // FR3, D14: a run the working-copy check does not apply to is adopted with no view
    def "FR3: a recorded transform key is adopted with no view, and its poll spawns the program alone"() {
        given:
        def request = deferred(Role.TRANSFORM)
        def recorded = executor().start(request).pendingKey()
        def resuming = resumed()
        int before = launches.get()

        when:
        resuming.adopt(request, recorded)
        def polled = resuming.poll(request, recorded)

        then:
        polled.status() == Status.COMPLETED
        launches.get() == before + 1
    }

    // FR3, D14 as amended 2026-11-13: with no expected HEAD and nothing registered there is no commit to stand on
    def "FR3: a recorded verdict key adopted by a run with no registered commit and no expected HEAD is refused, spawning no program"() {
        given:
        def request = deferred(Role.VERDICT)
        def recorded = executor().start(request).pendingKey()
        def resuming = executor([
            com.github.oinsio.gnomish.executorcontract.Capability.POLL
        ], environment,
        PendingScope.unscoped(new PendingRuns()))
        int before = launches.get()

        when:
        resuming.adopt(request, recorded)
        def polled = resuming.poll(request, recorded)

        then:
        polled.failureClass() == FailureClass.INFRASTRUCTURE
        polled.reason().contains("the pending key 'reference-deferred' recorded for this check cannot be adopted: "
                + 'the round has no registered commit the working copy stands on')
        launches.get() == before + 1
    }

    // Scenario: executor/wire-contract / Recorded key is polled by a resuming instance
    // FR3, D14: a new run adopts the recorded key over its clean working copy on the expected HEAD and polls it; no start
    def "FR3: an instance that did not start the run adopts the recorded key and spawns the program with poll and that key"() {
        given: 'the first instance started the run; the second never saw it'
        def request = deferred(Role.VERDICT)
        def recorded = executor().start(request).pendingKey()
        def resuming = resumed()
        int before = launches.get()

        when:
        resuming.adopt(request, recorded)
        def polled = resuming.poll(request, recorded)

        then: 'the reference answered, which it does only for its own key under the poll word'
        polled.status() == Status.COMPLETED
        polled.answer()?.path('kind')?.asText() == 'verdict'

        and: 'the view at adoption, the poll and the judging view: no start'
        launches.get() == before + 3
    }

    // Scenario: executor/wire-contract / Adopted key is judged against the view taken at adoption
    // FR3, FR11, D14: a change after the adoption refuses the answer of the poll that ends the run
    def "FR11: an adopted run whose working copy changed after the adoption is refused at the poll that ends it, naming the path"() {
        given:
        def request = deferred(Role.VERDICT)
        def recorded = executor().start(request).pendingKey()
        def resuming = resumed()
        resuming.adopt(request, recorded)

        when: 'the program, still running, writes into the working copy'
        Files.writeString(repo.resolve('tracked.txt'), 'the judge was here\n', java.nio.file.StandardOpenOption.APPEND)
        def polled = resuming.poll(request, recorded)

        then:
        polled.status() == Status.FAILED
        polled.failureClass() == FailureClass.INFRASTRUCTURE
        polled.answer() == null
        polled.reason().contains('a verdict run must leave the working copy unchanged (policy readOnly); the run changed tracked.txt')
    }

    // Scenario: executor/wire-contract / Adoption over a changed working copy is refused
    // FR3, FR11, D14: a host worktree with a killed run's leftovers refuses the adoption, and the poll spawns nothing
    def "FR3: a recorded key adopted over a working copy holding changes against HEAD is refused naming the key and the paths, spawning nothing"() {
        given: 'the first instance started the run and left writes behind'
        def request = deferred(Role.VERDICT)
        def recorded = executor().start(request).pendingKey()
        Files.writeString(repo.resolve('leftover.txt'), 'half a report\n')
        Files.writeString(repo.resolve('tracked.txt'), 'edited\n')
        def resuming = resumed()
        int before = launches.get()

        when:
        resuming.adopt(request, recorded)
        def polled = resuming.poll(request, recorded)

        then:
        polled.status() == Status.FAILED
        polled.failureClass() == FailureClass.INFRASTRUCTURE
        polled.reason().contains("program executor 'acme': the pending key 'reference-deferred' recorded for this "
                + "check cannot be adopted: the working copy holds changes against HEAD other than the factory's "
                + 'state.json lag: tracked.txt, leftover.txt')

        and: 'only the view at adoption ran; the program was never spawned'
        launches.get() == before + 1

        and: 'the refusal settled the key: polling it again is an unknown key'
        resuming.poll(request, recorded).reason().contains("the pending key 'reference-deferred' is unknown")
        launches.get() == before + 1
    }
}
