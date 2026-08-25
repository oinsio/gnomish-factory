package com.github.oinsio.gnomish.gitobjects

import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR25 of add-sandbox-core, FR13 of bound-subprocess-commands: {@link GitExec}'s interruption
 * contract, which the migration onto the shared supervisor leaves unchanged — the interrupt flag is
 * restored for callers up the stack, the child process is not left orphaned, and the failure
 * surfaces as a {@link GitObjectsException} carrying its cause rather than as an exit code.
 *
 * <p>The mechanics under the contract — the two-phase tree kill and the reap — belong to
 * {@code ProcessSupervisorInterruptSpec} now; what is verified here is only this library's
 * translation of a named termination into its own exception.
 */
class GitExecInterruptSpec extends Specification {

    def "FR25: an interrupt while awaiting the subprocess restores the flag and destroys the process"() {
        given: 'a long-lived child process standing in for a slow git command'
        def process = new ProcessBuilder('sleep', '600').start()

        and: 'stdin/stderr pump threads that have already finished'
        def done = finishedThread()

        and: 'the awaiting thread is pre-interrupted, so the supervised wait ends as INTERRUPTED'
        Thread.currentThread().interrupt()

        when:
        GitExec.await(process, done, done)

        then: 'the interruption surfaces loudly, carrying its cause'
        def e = thrown(GitObjectsException)
        e.cause instanceof InterruptedException

        and: 'the interrupt flag was restored for callers up the stack (read-and-clear here)'
        Thread.interrupted()

        and: 'the child process was destroyed, not left orphaned'
        !process.alive

        cleanup: 'never leak the process or the interrupt flag, whichever assertion failed'
        process.destroyForcibly()
        Thread.interrupted()
    }

    // FR13: the second interrupt site — the process is already gone, so only the flag and the loud
    // failure are owed; a pre-interrupted join makes the path deterministic rather than a race.
    def "FR13: an interrupt while joining the pump threads restores the flag and surfaces loudly"() {
        given: 'a process that has already exited, so the supervised wait resolves as EXITED'
        def process = new ProcessBuilder('true').start()
        process.waitFor(5, TimeUnit.SECONDS)

        and: 'a pump thread still busy with slow work'
        def pump = sleepingThread()

        and: 'the joining thread is pre-interrupted, so join throws immediately'
        Thread.currentThread().interrupt()

        when:
        GitExec.await(process, pump, pump)

        then: 'the interruption surfaces loudly, carrying its cause'
        def e = thrown(GitObjectsException)
        e.cause instanceof InterruptedException

        and: 'the interrupt flag was restored for callers up the stack (read-and-clear here)'
        Thread.interrupted()

        cleanup: 'never leak the pump thread or the interrupt flag, whichever assertion failed'
        pump.interrupt()
        Thread.interrupted()
    }

    private static Thread finishedThread() {
        def thread = new Thread({ } as Runnable)
        thread.start()
        thread.join()
        thread
    }

    private static Thread sleepingThread() {
        def thread = new Thread({
            try {
                Thread.sleep(30_000)
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
            }
        } as Runnable)
        thread.daemon = true
        thread.start()
        thread
    }
}
