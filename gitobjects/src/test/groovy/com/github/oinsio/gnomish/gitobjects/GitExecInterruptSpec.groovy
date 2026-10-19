package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.WritingHolder
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR25 of add-sandbox-core, FR13 of bound-subprocess-commands, FR19 of
 * fix-terminal-receipt-convergence: {@link GitExec}'s interruption contract — the interrupt flag is
 * restored for callers up the stack, the child process is not left orphaned, and the failure
 * surfaces as a {@link GitObjectsInterruptedException} carrying its cause rather than as an exit
 * code — by its own type, so a caller that degrades on an unreadable document cannot fold "git was
 * never allowed to answer" into that answer.
 *
 * <p>The mechanics under the contract — the two-phase tree kill and the reap — belong to
 * {@code ProcessSupervisorInterruptSpec}; what is verified here is this library's translation of a
 * named termination, and of an interrupt cutting the post-exit join (design D17), into its own
 * exception. Driven on {@link FakeProcess}, so the order of exit and interrupt is the spec's.
 */
class GitExecInterruptSpec extends Specification {

    private static final ProcessSupervisor SUPERVISOR = new ProcessSupervisor(Duration.ofMillis(100))

    @TempDir
    Path gitDir

    def "FR25: an interrupt while awaiting the subprocess restores the flag and destroys the process"() {
        given: 'a git process that never exits on its own'
        FakeProcess process = FakeProcess.running()

        and: 'the awaiting thread is pre-interrupted, so the supervised wait ends as INTERRUPTED'
        Thread.currentThread().interrupt()

        when:
        exec(process).run(['cat-file', '-e', 'HEAD'])

        then: 'the interruption surfaces loudly, by its own type and carrying its cause'
        def e = thrown(GitObjectsInterruptedException)
        e.cause instanceof InterruptedException

        and: 'the interrupt flag was restored for callers up the stack (read-and-clear here)'
        Thread.interrupted()

        and: 'the child process was destroyed, not left orphaned'
        !process.alive

        cleanup: 'never leak the process or the interrupt flag, whichever assertion failed'
        process.destroyForcibly()
        Thread.interrupted()
    }

    // FR19: the second interrupt site — the process has exited and a holder keeps the pipes, writing
    // to stdout so the drain stays open to the bound (D4 of make-pipe-reads-non-parking); the
    // interrupt lands in the post-exit join, the wait was reached, and only the flag and the loud
    // failure are owed.
    def "FR19: an interrupt during the post-exit join restores the flag, surfaces loudly and stops the drains"() {
        given: 'git exits cleanly while a descendant holds both pipes and keeps writing to stdout, and the interrupt arrives once the wait saw the exit'
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        process.onWaitReturn { Thread.currentThread().interrupt() }
        process.exit(0)

        when:
        long startedAt = System.nanoTime()
        exec(process).run(['cat-file', '-e', 'HEAD'])

        then: 'the interruption surfaces loudly, by its own type and carrying its cause'
        def e = thrown(GitObjectsInterruptedException)
        e.cause instanceof InterruptedException

        and: 'the interrupt flag was restored for callers up the stack (read-and-clear here)'
        Thread.interrupted()

        and: 'the join was cut by the interrupt, not waited out to the bound'
        Duration.ofNanos(System.nanoTime() - startedAt) <SUPERVISOR.postExitBound()

        cleanup: 'never leak the holder or the interrupt flag, whichever assertion failed'
        process.release()
        Thread.interrupted()
    }

    // FR19, FR23: the launch owner writes the input file before the start; an interrupt already set
    // when it does is still this library's interruption, not an unlaunchable binary.
    def "FR23: an interrupt set before a run with stdin surfaces as the interruption, not as a launch failure"() {
        given: 'the real launch owner and a real git dir'
        GitExec exec = new GitExec(gitDir, 'git', SUPERVISOR, ProcessLaunch.grouped(gitDir))

        when:
        Thread.currentThread().interrupt()
        exec.run(['hash-object', '--stdin'], 'blob'.bytes, [:], -1)

        then:
        thrown(GitObjectsInterruptedException)
        Thread.interrupted()

        cleanup:
        Thread.interrupted()
    }

    private GitExec exec(FakeProcess process) {
        new GitExec(gitDir, 'git', SUPERVISOR, { builder, stdin ->
            process
        } as ProcessLaunch)
    }
}
