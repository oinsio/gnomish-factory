package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Two polls of one pending key on one {@link ProgramExecutor} at once (FR23 of
 * define-executor-contract; design D3; {@code lock-scope.md}, "phase 1 must claim"): the poll takes
 * its run out of {@link PendingRuns} before it spawns, so a second poll of the same key, arriving
 * while the first one's process is being launched, finds no run and spawns nothing. The order is
 * made, not assumed: the first poll's launch parks on a latch until the second poll has returned,
 * and the second poll runs on the spec's own thread only after the first reached its launch. The
 * program is the stand-in reference executor, which completes a poll of its {@code
 * reference-deferred} key.
 */
class ProgramPollConcurrencySpec extends Specification {

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    AtomicInteger pollLaunches = new AtomicInteger()

    CountDownLatch firstPollLaunching = new CountDownLatch(1)

    CountDownLatch releaseFirstPoll = new CountDownLatch(1)

    HostTaskExecutionEnvironment environment

    def setup() {
        def gated = { ProcessBuilder builder, StdinSource stdin ->
            if (builder.command().last() == 'poll' && pollLaunches.incrementAndGet() == 1) {
                firstPollLaunching.countDown()
                assert releaseFirstPoll.await(30, TimeUnit.SECONDS)
            }
            ProcessLaunch.grouped().start(builder, stdin)
        } as ProcessLaunch
        environment = new HostTaskExecutionEnvironment(workspace, new VirtualClock(), TestChildEnvironment.hostAllowlist(), gated)
        environment.materialize('gnomish/TASK-7', null)
    }

    def cleanup() {
        releaseFirstPoll.countDown()
        environment.dispose()
    }

    private static ExecutorRequest deferred() {
        new ExecutorRequest(Role.TRANSFORM, new Workspace('.'), new Policy.WorkspaceWrite(), RoleInputs.transform('build it'),
                ExecutorContractJson.settingsTree([defer: true]), ProgramBounds.DEFAULTS.hints(), [], null)
    }

    // FR23: a poll claims its pending run before it spawns, so a concurrent poll of the same key is unknown
    def "FR23: a second poll of a key whose first poll is still launching is refused as unknown and spawns nothing"() {
        given:
        def executor = new ProgramExecutor(ProgramFixtures.declaration([
            StandIn.executor('reference').toString()
        ]),
        ProgramFixtures.describe([Capability.POLL]), [], environment, ProgramFixtures.equipment(stderrParent))
        def request = deferred()
        def key = executor.start(request).pendingKey()

        when: 'the first poll reaches its launch and parks there'
        CompletableFuture<ExecutorResult> first = CompletableFuture.supplyAsync({
            executor.poll(request, key)
        })
        assert firstPollLaunching.await(30, TimeUnit.SECONDS)

        and: 'the second poll of the same key runs to its end meanwhile'
        def second = executor.poll(request, key)
        releaseFirstPoll.countDown()

        then: 'the second found no run to continue; the first ended the run with the one poll spawned'
        second.status() == Status.FAILED
        second.failureClass() == FailureClass.INFRASTRUCTURE
        second.reason().contains("the pending key 'reference-deferred' is unknown")
        first.get(30, TimeUnit.SECONDS).status() == Status.COMPLETED
        pollLaunches.get() == 1
    }
}
