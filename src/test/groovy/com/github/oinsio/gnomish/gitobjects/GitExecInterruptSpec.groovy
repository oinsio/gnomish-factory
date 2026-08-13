package com.github.oinsio.gnomish.gitobjects

import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR25: {@link GitExec}'s interruption contract while awaiting a git subprocess — the interrupt
 * flag is restored for callers up the stack and the child process is destroyed rather than left
 * orphaned, with the failure surfacing as a {@link GitObjectsException}.
 */
class GitExecInterruptSpec extends Specification {

    def "FR25: an interrupt while awaiting the subprocess restores the flag and destroys the process"() {
        given: 'a long-lived child process standing in for a slow git command'
        def process = new ProcessBuilder('sleep', '600').start()

        and: 'stdin/stderr pump threads that have already finished'
        def done = new Thread({ } as Runnable)
        done.start()
        done.join()

        and: 'the awaiting thread is pre-interrupted, so waitFor throws immediately'
        Thread.currentThread().interrupt()

        when:
        GitExec.await(process, done, done)

        then: 'the interruption surfaces loudly, carrying its cause'
        def e = thrown(GitObjectsException)
        e.cause instanceof InterruptedException

        and: 'the interrupt flag was restored for callers up the stack (read-and-clear here)'
        Thread.interrupted()

        and: 'the child process was destroyed, not left orphaned'
        process.waitFor(5, TimeUnit.SECONDS)

        cleanup: 'never leak the process or the interrupt flag, whichever assertion failed'
        process.destroyForcibly()
        Thread.interrupted()
    }
}
