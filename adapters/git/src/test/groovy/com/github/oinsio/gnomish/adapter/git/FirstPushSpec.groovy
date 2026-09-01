package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.FirstPushFailedException
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.time.MovableClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7 of harden-task-branch-contract: the first push of a newly created task branch is the one
 * load-bearing push in the factory — bounded retries, a remote-tip re-check before any re-push,
 * and an abort on exhaustion so no round ever runs on a branch origin has never seen.
 */
class FirstPushSpec extends Specification implements BareGitRepoFixture {

    private static final String SHA = '1111111111111111111111111111111111111111'

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    /** Virtual time: the suppressor's roll-up interval is minutes long and no spec may sleep. */
    MovableClock clock = new MovableClock(Instant.parse('2026-08-31T10:00:00Z'))

    RepeatSuppressor suppressor = new RepeatSuppressor(clock, RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL)

    /** The production budget with its sleeps virtualized, so exhaustion is instant under test. */
    private static GitInfrastructureRetry instantRetry() {
        new GitInfrastructureRetry({ Duration ignored -> } as Sleeper,
        GitInfrastructureRetry.DEFAULT_ATTEMPTS, Duration.ofMillis(1))
    }

    def "FR7: a first push that lands leaves the branch on origin"() {
        given:
        def bare = initBareRepo(tempDir, 'origin.git')
        def clone = initWorkingRepo(tempDir, 'clone')
        commit(clone, 'a.txt', 'first')
        runner.run(clone, 'remote', 'add', 'origin', bare.toString())
        runner.run(clone, 'branch', 'gnomish/PROJ-1')

        when:
        new FirstPush(runner, instantRetry(), suppressor).deliver('PROJ-1', clone, 'gnomish/PROJ-1')

        then:
        noExceptionThrown()
        runner.run(bare, 'rev-parse', '--verify', '--quiet', 'refs/heads/gnomish/PROJ-1').exitCode() == 0
    }

    def "UX3: a purely local run has no origin to be load-bearing about and stays a silent no-op"() {
        given:
        def clone = initWorkingRepo(tempDir, 'clone-no-origin')
        commit(clone, 'a.txt', 'first')
        runner.run(clone, 'branch', 'gnomish/PROJ-2')

        when:
        new FirstPush(runner, instantRetry(), suppressor).deliver('PROJ-2', clone, 'gnomish/PROJ-2')

        then:
        noExceptionThrown()
    }

    def "FR7: a push origin never received aborts after the budget, so no round starts on it"() {
        given: 'a git whose pushes fail and whose origin demonstrably lacks the branch'
        def gitBinary = dispatchingGit('')
        def clone = initWorkingRepo(tempDir, 'clone-undelivered')
        commit(clone, 'a.txt', 'first')

        when:
        new FirstPush(new GitProcessRunner(gitBinary.toString()), instantRetry(), suppressor)
                .deliver('PROJ-3', clone, 'gnomish/PROJ-3')

        then:
        def ex = thrown(FirstPushFailedException)
        ex.message.contains('gnomish/PROJ-3')
        ex.message.contains('does not carry')

        and: 'every attempt of the budget was spent, and no more'
        Files.readAllLines(tempDir.resolve('push-count.txt')).size() == GitInfrastructureRetry.DEFAULT_ATTEMPTS
    }

    def "FR4, FR12: an origin that stays down never reaches the console — the abort is the report"() {
        given: 'a git whose pushes fail and whose origin demonstrably lacks the branch'
        def gitBinary = dispatchingGit('')
        def clone = initWorkingRepo(tempDir, 'clone-flooding')
        commit(clone, 'a.txt', 'first')
        def logs = LogCaptureSupport.attach(FirstPush, Level.DEBUG)

        when: 'the whole retry budget is spent against it'
        new FirstPush(new GitProcessRunner(gitBinary.toString()), instantRetry(), suppressor)
                .deliver('PROJ-6', clone, 'gnomish/PROJ-6')

        then: 'the exception is what the run reports; the attempts say nothing at WARN'
        thrown(FirstPushFailedException)
        logs.list.every { it.level == Level.DEBUG }

        and: 'one attempt line names the fault, and the rest are counted rather than repeated'
        def attempts = logs.list.findAll {
            it.formattedMessage.contains('re-checking the remote tip')
        }
        attempts.size() == GitInfrastructureRetry.DEFAULT_ATTEMPTS
        attempts[0].formattedMessage.contains('taskId=PROJ-6')
        attempts.last().formattedMessage.contains("${GitInfrastructureRetry.DEFAULT_ATTEMPTS}x")

        cleanup:
        logs.detach()
    }

    // FR4: the suppressor outlives one delivery, so the streak key must namespace by branch —
    // otherwise two tasks pushing against the same down origin share one count, and the second
    // task's first failure reads as a continuation of a streak that is not its own.
    def "FR4: two branches failing against the same origin keep separate streaks"() {
        given: 'a git whose pushes fail, and two tasks delivered through one shared suppressor'
        def gitBinary = dispatchingGit('')
        def clone = initWorkingRepo(tempDir, 'clone-two-branches')
        commit(clone, 'a.txt', 'first')
        def push = new FirstPush(new GitProcessRunner(gitBinary.toString()), instantRetry(), suppressor)

        when: 'the first task spends its whole budget'
        try {
            push.deliver('PROJ-A', clone, 'gnomish/PROJ-A')
        } catch (FirstPushFailedException ignored) {
        }

        and: 'then a second, unrelated task does the same'
        def logs = LogCaptureSupport.attach(FirstPush, Level.DEBUG)
        try {
            push.deliver('PROJ-B', clone, 'gnomish/PROJ-B')
        } catch (FirstPushFailedException ignored) {
        }
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'the second task counts its own attempts from one, not from where the first left off'
        def attempts = events.findAll {
            it.formattedMessage.contains('re-checking the remote tip')
        }
        attempts.size() == GitInfrastructureRetry.DEFAULT_ATTEMPTS
        attempts.last().formattedMessage.contains("${GitInfrastructureRetry.DEFAULT_ATTEMPTS}x")
        attempts.every { it.formattedMessage.contains('taskId=PROJ-B') }
    }

    def "FR4: a push that lands after a failed streak announces the recovery"() {
        given: 'an origin that refuses the first attempt and carries the tip on the re-check'
        def gitBinary = dispatchingGit("${SHA}\trefs/heads/gnomish/PROJ-7")
        def clone = initWorkingRepo(tempDir, 'clone-recovering')
        commit(clone, 'a.txt', 'first')
        def logs = LogCaptureSupport.attach(FirstPush)

        when:
        new FirstPush(new GitProcessRunner(gitBinary.toString()), instantRetry(), suppressor)
                .deliver('PROJ-7', clone, 'gnomish/PROJ-7')

        then: 'the console does not end on the failure: one INFO says the branch got there'
        def recovery = logs.list.find { it.level == Level.INFO }
        recovery.formattedMessage.contains('first push landed after 1 failed attempt(s)')
        recovery.formattedMessage.contains('taskId=PROJ-7')

        cleanup:
        logs.detach()
    }

    def "FR7: a push whose outcome was never established but which landed is success, not a re-push"() {
        given: 'a git whose push reports failure while origin already carries the local tip'
        def gitBinary = dispatchingGit("${SHA}\trefs/heads/gnomish/PROJ-4")
        def clone = initWorkingRepo(tempDir, 'clone-landed')
        commit(clone, 'a.txt', 'first')

        when:
        new FirstPush(new GitProcessRunner(gitBinary.toString()), instantRetry(), suppressor)
                .deliver('PROJ-4', clone, 'gnomish/PROJ-4')

        then: 'the re-check settled it: no abort, and the budget stopped at the first attempt'
        noExceptionThrown()
        Files.readAllLines(tempDir.resolve('push-count.txt')).size() == 1
    }

    def "FR7: a push of a branch the clone does not hold is a caller defect, named as one"() {
        given: 'a git whose push fails and whose clone cannot resolve the branch at all'
        def gitBinary = missingBranchGit()
        def clone = initWorkingRepo(tempDir, 'clone-missing-branch')
        commit(clone, 'a.txt', 'first')

        when:
        new FirstPush(new GitProcessRunner(gitBinary.toString()), instantRetry(), suppressor)
                .deliver('PROJ-5', clone, 'gnomish/PROJ-5')

        then: 'the abort says the branch is missing locally, not that origin refused it'
        def ex = thrown(FirstPushFailedException)
        ex.message.contains('holds no gnomish/PROJ-5 to deliver')
    }

    /** A git with a configured origin, a failing push, and no local branch to resolve. */
    private Path missingBranchGit() {
        def script = tempDir.resolve('missing-branch-git.sh')
        script.toFile().text = '''#!/bin/sh
for a in "$@"; do
  case "$a" in
    push) echo 'fatal: unable to access origin' 1>&2; exit 128;;
    rev-parse) exit 1;;
    remote) echo 'https://example.invalid/repo.git'; exit 0;;
  esac
done
exit 1
'''
        script.toFile().setExecutable(true)
        script
    }

    /**
     * A git that fails every push while answering the re-check: the combination that separates
     * "the push did not land" from "the push landed and said nothing", which a real remote cannot
     * be talked into producing on demand.
     *
     * @param lsRemoteOutput what {@code ls-remote} reports — a ref line for the landed case, empty
     *     for the demonstrably-absent one
     */
    private Path dispatchingGit(String lsRemoteOutput) {
        def script = tempDir.resolve("dispatching-git-${lsRemoteOutput.isEmpty() ? 'absent' : 'landed'}.sh")
        script.toFile().text = """#!/bin/sh
for a in "\$@"; do
  case "\$a" in
    push) echo x >> '${tempDir.resolve('push-count.txt')}'; echo 'fatal: unable to access origin' 1>&2; exit 128;;
    ls-remote) printf '%s' '${lsRemoteOutput}'; echo; exit 0;;
    merge-base) exit 0;;
    rev-parse) echo '${SHA}'; exit 0;;
    remote) echo 'https://example.invalid/repo.git'; exit 0;;
  esac
done
exit 1
"""
        script.toFile().setExecutable(true)
        script
    }
}
