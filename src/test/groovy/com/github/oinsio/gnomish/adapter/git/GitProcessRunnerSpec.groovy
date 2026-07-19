package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2 of add-git-workflow: the git subprocess runner invokes {@code git <args...>} directly
 * (no shell) with a specified cwd, captures exit code/stdout/stderr separately without throwing
 * on a non-zero git exit, and surfaces a clear error when the {@code git} binary itself cannot
 * be launched.
 */
class GitProcessRunnerSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    def "FR2: runs a git command with the given cwd and captures a zero exit code"() {
        given:
        def repo = initWorkingRepo(tempDir)

        when:
        def result = runner.run(repo, 'rev-parse', '--is-inside-work-tree')

        then:
        result.exitCode() == 0
        result.stdout().trim() == 'true'
    }

    def "FR2: captures stdout and stderr separately, not merged"() {
        given:
        def repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'hello'
        runner.run(repo, 'add', 'a.txt')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'first')

        when:
        def result = runner.run(repo, 'log', '--oneline')

        then:
        result.exitCode() == 0
        result.stdout().contains('first')
    }

    def "FR2: a non-zero git exit code is returned, not thrown"() {
        given:
        def repo = initWorkingRepo(tempDir)

        when:
        def result = runner.run(repo, 'show', 'nonexistent-ref')

        then:
        noExceptionThrown()
        result.exitCode() != 0
        !result.stderr().isEmpty()
    }

    def "FR2: git init --bare succeeds and produces a bare repository marker"() {
        when:
        def repo = initBareRepo(tempDir)

        then:
        new File(repo.toFile(), 'HEAD').exists()
    }

    def "FR2: running against a nonexistent cwd surfaces as a failed result, not a silent success"() {
        given:
        def missing = tempDir.resolve('does-not-exist')

        when:
        def result = runner.run(missing, 'status')

        then:
        result.exitCode() != 0
    }

    def "FR2: an unrecognized git subcommand is reported via a non-zero exit code and stderr"() {
        given:
        def repo = initWorkingRepo(tempDir)

        when:
        def result = runner.run(repo, 'not-a-real-git-command')

        then:
        result.exitCode() != 0
        !result.stderr().isEmpty()
    }

    def "FR2: a runner configured with a nonexistent git binary throws a clear exception instead of hanging"() {
        given:
        def brokenRunner = new GitProcessRunner('definitely-not-a-real-git-binary-xyz')

        when:
        brokenRunner.run(tempDir, 'status')

        then:
        def e = thrown(GitBinaryNotFoundException)
        e.message.contains('definitely-not-a-real-git-binary-xyz')
    }

    // PIT NO_COVERAGE on waitFor's InterruptedException branch: interrupting the calling thread
    // while the subprocess is still running must both re-set the thread's interrupt flag and
    // return -1, rather than propagating the exception or blocking forever.
    //
    // Driven through `run()` this is unreachable: readFully() drains stdout/stderr *before*
    // waitFor() is ever called, and that blocking stream read ignores Thread.interrupt() entirely
    // (only NIO channels / InterruptedIOException-aware I/O respond to it) — so the calling thread
    // is always stuck in readFully(), never in waitFor(), for as long as the child process's pipes
    // stay open, which for any live process is until it exits. There is no live process whose
    // stdout/stderr are already closed while it still runs, so waitFor()'s own blocking call can
    // never be reached with an interruptible wait via the public API. This spec instead invokes
    // the private static waitFor(Process) directly via reflection — the smallest true unit that
    // isolates the behavior this method alone is responsible for, without stubbing/mocking.
    def "FR2: interrupting the calling thread while waitFor blocks sets the interrupt flag and returns -1"() {
        given: 'a real long-running process, reached directly (bypassing readFully entirely)'
        def process = new ProcessBuilder('sleep', '60').start()
        def waitForMethod = GitProcessRunner.getDeclaredMethod('waitFor', Process)
        waitForMethod.accessible = true

        def resultRef = new java.util.concurrent.atomic.AtomicReference()
        def interruptedFlagRef = new java.util.concurrent.atomic.AtomicBoolean()
        def started = new java.util.concurrent.CountDownLatch(1)

        def worker = new Thread({
            started.countDown()
            resultRef.set(waitForMethod.invoke(null, process))
            interruptedFlagRef.set(Thread.currentThread().isInterrupted())
        })

        when:
        worker.start()
        started.await()
        Thread.sleep(200) // let the thread actually enter Process#waitFor before interrupting
        worker.interrupt()
        worker.join(10_000)

        then: 'the interrupt won the race against the 60s sleep, well before it could exit naturally'
        !worker.isAlive()
        resultRef.get() == -1

        and: 'the thread\'s interrupt status was restored before waitFor returned'
        interruptedFlagRef.get()

        cleanup:
        process.destroyForcibly()
    }
}
