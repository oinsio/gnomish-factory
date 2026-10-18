package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.OrderedExit
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.logtext.ShutdownPhase
import com.github.oinsio.gnomish.subprocess.CaptureRunner
import com.github.oinsio.gnomish.subprocess.Captured
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * FR21, design D19 of fix-terminal-receipt-convergence (fourth amendment): the shutdown step, as
 * production wires it — {@link RealProcessTreeKiller} run by the generic signal hook ({@code
 * OrderedExit.onSignal}) and by {@link ServeShutdown} — kills the JVM's descendants and then sweeps
 * every live group, so an orphan the descendant snapshot misses, in a group no waiter has swept,
 * is gone when the step returns. The processes are real on purpose: {@code leaky} leaks a {@code
 * sleep 30} out of its own tree and stalls, and what is measured is what the OS does with that
 * orphan's group. The orphan sleeps 30 s and nothing else here would end it in the bounds asserted.
 */
class ShutdownSweepSpec extends ServeShutdownSpecBase {

    List<String> steps = []

    def setup() {
        ShutdownPhase.reset()
        OrderedExit.install({
            steps << 'closeContext'
        }, {
            steps << 'stopLogging'
        }, new RealProcessTreeKiller())
    }

    def cleanup() {
        ShutdownPhase.reset()
        OrderedExit.install({}, {}, {})
    }

    // Scenario: lifecycle/subprocess-supervision / A child in its own session is ended by the factory
    def "FR21: a run-shaped signal hook kills the child and sweeps the orphan its snapshot misses, before the context closes"() {
        given: 'leaky started through the launch owner with no waiter, its orphan out of every snapshot'
        Path pidFile = loopsRoot.resolve('holder.pid')
        Process child = ProcessLaunch.grouped(loopsRoot).start(leaky(pidFile), StdinSource.NONE)
        ProcessHandle holder = reparented(child, pidFile)

        expect:
        holder.isAlive()
        ProcessHandle.current().descendants().noneMatch {
            it.pid() == holder.pid()
        }

        when: 'the operator interrupts the command, which owns no hook of its own'
        OrderedExit.onSignal()

        then: 'the snapshot kill ended the child cooperatively, and the sweep ended the orphan'
        child.waitFor(10, TimeUnit.SECONDS)
        child.exitValue() == 143
        ended(holder)

        and: 'the context closed and logging stopped after the step'
        steps == ['closeContext', 'stopLogging']

        cleanup:
        child?.destroyForcibly()
        holder?.destroyForcibly()
    }

    // Scenario: lifecycle/subprocess-supervision / A daemon's stop sweeps the groups no waiter has swept
    def "FR21: serve's shutdown sweeps the orphan's group while its waiter is still inside the post-exit bound"() {
        given: 'a waiter capturing leaky, whose post-exit bound outlasts every wait below'
        Path pidFile = loopsRoot.resolve('holder.pid')
        CompletableFuture<Process> launched = new CompletableFuture<>()
        ProcessLaunch grouped = ProcessLaunch.grouped(loopsRoot)
        ProcessLaunch recording = { ProcessBuilder command, StdinSource stdin ->
            Process started = grouped.start(command, stdin)
            launched.complete(started)
            started
        } as ProcessLaunch
        CaptureRunner runner = new CaptureRunner(new ProcessSupervisor(Duration.ofSeconds(25)), recording)
        ProcessBuilder builder = leaky(pidFile)
        CompletableFuture<Captured> waiter = CompletableFuture.supplyAsync {
            runner.run(builder, null)
        }
        Process child = launched.get(10, TimeUnit.SECONDS)
        ProcessHandle holder = reparented(child, pidFile)

        and: 'the daemon\'s own shutdown, over the production step'
        ServeShutdown shutdown = new ServeShutdown(
                new SlotLedger(1, new VirtualClock()),
                new ClaimLossFlag(),
                Duration.ofMillis(50),
                new RealProcessTreeKiller(),
                inertLoops())

        expect: 'the waiter has not swept, and no snapshot lists the orphan'
        !waiter.isDone()
        ProcessHandle.current().descendants().noneMatch {
            it.pid() == holder.pid()
        }

        when:
        shutdown.shutdown(null)

        then: 'the orphan is gone, long before the waiter\'s bound would have let it sweep'
        ended(holder)

        and: 'the waiter, its pipe released, returns the child\'s cooperative end'
        Captured captured = waiter.get(10, TimeUnit.SECONDS)
        captured.termination() == Termination.EXITED
        captured.exitCode() == 143

        cleanup:
        child?.destroyForcibly()
        holder?.destroyForcibly()
    }

    private static ProcessBuilder leaky(Path pidFile) {
        ProcessBuilder builder = new ProcessBuilder(StandIn.process('leaky').toString(), pidFile.toString())
        TestChildEnvironment.cleared(builder)
        builder
    }

    /** The orphan leaky leaked, once it has left the child's tree and the child stalls in its own sleep. */
    private static ProcessHandle reparented(Process child, Path pidFile) {
        eventually {
            Files.exists(pidFile) && !Files.readString(pidFile).isBlank()
        }
        ProcessHandle holder = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()
        eventually {
            List<Long> below = child.descendants().map { it.pid() }.toList()
            !below.isEmpty() && !below.contains(holder.pid())
        }
        holder
    }

    /** Whether the orphan has ended, waiting up to ten seconds for its exit — less than its own sleep. */
    private static boolean ended(ProcessHandle holder) {
        holder.onExit().get(10, TimeUnit.SECONDS)
        !holder.isAlive()
    }

    /** Polls what leaky does on its own schedule (its pid file, its fork), up to ten seconds. */
    private static void eventually(Closure<Boolean> condition) {
        long deadline = System.nanoTime() + 10_000_000_000L
        while (!condition.call()) {
            assert System.nanoTime() <deadline
            Thread.sleep(20)
        }
    }
}
