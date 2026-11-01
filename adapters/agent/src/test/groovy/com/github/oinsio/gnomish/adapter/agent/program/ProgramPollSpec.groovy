package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.adapter.git.AdversarialLocalConfig
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.PendingKey
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.gitobjects.LocalGitRepoFixture
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The poll wire form of {@link ProgramExecutor} on the real host path (FR23, FR11, NFR-S4 of
 * define-executor-contract; design D3, D6): the program is the stand-in reference executor, which
 * returns {@code pending} with the key {@code reference-deferred} when its settings ask {@code defer},
 * answers a poll whose {@code GNOMISH_PENDING_KEY} carries that key, and refuses any other key. The
 * working copy is a real git repository carrying the adversarial local configuration, so the view a
 * pending judge is judged against is git's own. Every launch is counted: a refused poll spawns none.
 * No feature rests on a scheduling order — each run is waited to its end before the next begins.
 */
class ProgramPollSpec extends Specification implements LocalGitRepoFixture {

    @TempDir
    Path tempDir

    @TempDir
    Path stderrParent

    Path repo

    HostTaskExecutionEnvironment environment

    AtomicInteger launches = new AtomicInteger()

    def setup() {
        repo = initWorkingRepo(tempDir)
        Files.writeString(repo.resolve('tracked.txt'), 'tracked\n')
        commitAll(repo)
        AdversarialLocalConfig.plant(repo)
        def counted = { ProcessBuilder builder, StdinSource stdin ->
            launches.incrementAndGet()
            ProcessLaunch.grouped().start(builder, stdin)
        } as ProcessLaunch
        environment = new HostTaskExecutionEnvironment(repo, new VirtualClock(), TestChildEnvironment.hostAllowlist(), counted)
        environment.materialize('gnomish/TASK-7', null)
    }

    def cleanup() {
        environment.dispose()
    }

    private ProgramExecutor executor(List<Capability> capabilities = [Capability.POLL]) {
        new ProgramExecutor(ProgramFixtures.declaration([
            StandIn.executor('reference').toString()
        ]),
        ProgramFixtures.describe(capabilities), [], environment, ProgramFixtures.equipment(stderrParent))
    }

    private static ExecutorRequest deferred(Role role) {
        def inputs = role == Role.VERDICT ? RoleInputs.verdict('Every method is documented.') : RoleInputs.transform('build it')
        Policy policy = role == Role.VERDICT ? new Policy.ReadOnly() : new Policy.WorkspaceWrite()
        new ExecutorRequest(role, new Workspace('.'), policy, inputs, ExecutorContractJson.settingsTree([defer: true]),
        ProgramBounds.DEFAULTS.hints(), [], null)
    }

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

        and: 'the poll that ended the run retired its key: a second poll is unknown and spawns nothing'
        executor.poll(request, started.pendingKey()).reason().contains("the pending key 'reference-deferred' is unknown")
        launches.get() == spawns

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
    // FR23, NG7: a key no start of this instance returned is refused, with no process — another instance's included
    def "FR23: a poll with a key this executor instance did not receive is failed/infrastructure naming the key, and spawns nothing"() {
        given: 'another instance issued the key'
        def issued = executor().start(deferred(Role.TRANSFORM)).pendingKey()
        int before = launches.get()

        when:
        def result = executor().poll(deferred(Role.TRANSFORM), issued)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains("the pending key 'reference-deferred' is unknown: no start of this executor instance returned it")
        launches.get() == before
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
