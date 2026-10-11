package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.subprocess.Termination
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import spock.lang.Specification

/**
 * FR27 of supervise-daemon-loops-and-embed-dashboard (design D27): {@link ScriptedGit}, the one
 * in-process fake of {@link GitRunner}, answers by argv prefix the way the stand-in library does,
 * refuses an invocation it was not scripted for, logs every call, and blocks a stalled one on a
 * latch that a spec releases or interrupts.
 */
class ScriptedGitSpec extends Specification {

    private static final Path CWD = Path.of('/repo')

    def "FR27: the first row whose prefix matches answers, after the leading -c pairs are dropped"() {
        given:
        def git = new ScriptedGit()
                .answer([
                    'rev-parse',
                    '--git-common-dir'
                ], 0, '.git\n')
                .answer(['rev-parse'], 1)
                .answer(['remote', 'get-url', '*'], 0, 'https://example.invalid/repo.git\n')
                .answer([], 0, '', 'anything else\n')

        expect:
        result(git.run(CWD, '-c', 'a.b=c', 'rev-parse', '--git-common-dir')) == [0, '.git\n', '']
        result(git.run(CWD, 'rev-parse', 'HEAD')) == [1, '', '']
        result(git.run(CWD, 'remote', 'get-url', 'origin')) == [
            0,
            'https://example.invalid/repo.git\n',
            ''
        ]
        result(git.run(CWD, 'status')) == [0, '', 'anything else\n']
    }

    def "FR27: a glob word matches by shell rules at its position, and a longer prefix needs the arguments"() {
        given:
        def git = new ScriptedGit()
                .answer([
                    'rev-parse',
                    '--verify',
                    'refs/remotes/*'
                ], 0, 'abc\n')
                .answer(['rev-parse', '--verify', 'v?'], 0, 'tag\n')

        expect:
        result(git.run(CWD, 'rev-parse', '--verify', 'refs/remotes/origin/main'))[1] == 'abc\n'
        result(git.run(CWD, 'rev-parse', '--verify', 'v1'))[1] == 'tag\n'

        when: 'one argument short of every prefix'
        git.run(CWD, 'rev-parse', '--verify')

        then:
        thrown(AssertionError)
    }

    def "FR27: an invocation no row matches throws, naming its argv, and is still logged"() {
        given:
        def git = new ScriptedGit().answer(['fetch'], 0)

        when:
        git.run(CWD, '-c', 'x=y', 'push', 'origin', 'main')

        then:
        def e = thrown(AssertionError)
        e.message == 'ScriptedGit: no row matches: -c x=y push origin main'
        git.calls()*.argv == [
            [
                '-c',
                'x=y',
                'push',
                'origin',
                'main'
            ]
        ]
    }

    def "FR27: the transfer owner's value is matched and logged by its argv"() {
        given:
        def git = new ScriptedGit().answer(['fetch'], 128, '', 'fatal: refused\n')
        def transfer = new GitTransfer([
            '-c',
            'fetch.prune=false',
            'fetch',
            '--no-tags',
            'origin'
        ], new TreeMap())

        when:
        def answered = git.run(CWD, transfer)

        then:
        result(answered) == [128, '', 'fatal: refused\n']
        git.calls(['fetch'])*.command() == [
            [
                'fetch',
                '--no-tags',
                'origin'
            ]
        ]
        git.calls()*.cwd == [CWD]
    }

    def "FR27: the call log tells what ran and what never did"() {
        given:
        def git = new ScriptedGit().answer([], 0)

        when:
        git.run(CWD, 'status')
        git.run(CWD, '-c', 'a=b', 'push', 'origin')

        then:
        git.calls(['push'])*.toString() == ['-c a=b push origin']
        git.calls(['fetch']) == []
        git.calls()*.command() == [
            ['status'],
            ['push', 'origin']
        ]
    }

    def "FR27: a scripted sequence answers in turn and repeats its last answer"() {
        given:
        def git = new ScriptedGit().answers(['push'],
        GitCommandResult.of(1, '', 'transient\n'),
        GitCommandResult.of(0, '', ''))

        expect:
        (1..3).collect { git.run(CWD, 'push').exitCode() } == [1, 0, 0]
    }

    def "FR27: waiting for a call fails, and interrupts the worker, when the worker ends without making it"() {
        given:
        def git = new ScriptedGit().answer([], 0)
        def worker = Thread.ofPlatform().start { git.run(CWD, 'status') }
        worker.join(5_000)

        when:
        git.awaitCall(['push'], worker)

        then:
        def e = thrown(AssertionError)
        e.message.contains("never asked for 'push'")
    }

    def "FR27: a computed row answers with what holds when the call arrives"() {
        given:
        int exit = 0
        def git = new ScriptedGit().answer(['ls-remote']) {
            GitCommandResult.of(exit, '', '')
        }

        when:
        def before = git.run(CWD, 'ls-remote', 'origin').exitCode()
        exit = 128

        then:
        before == 0
        git.run(CWD, 'ls-remote', 'origin').exitCode() == 128
    }

    def "FR27: a stalled call blocks its thread until another thread releases the latch"() {
        given:
        def release = new CountDownLatch(1)
        def git = new ScriptedGit().stalls(['push'], release, GitCommandResult.of(0, 'landed\n', ''))

        when:
        GitCommandResult answered = null
        def pusher = Thread.ofPlatform().start {
            answered = git.run(CWD, 'push', 'origin')
        }
        git.awaitCall(['push'], pusher)
        def doneBefore = !pusher.alive
        release.countDown()

        pusher.join(5_000)

        then:
        !doneBefore
        result(answered) == [0, 'landed\n', '']
    }

    def "FR27: an interrupt of the stalled thread answers INTERRUPTED at once and restores the flag"() {
        given:
        def git = new ScriptedGit().stalls(['fetch'], new CountDownLatch(1))
        GitCommandResult answered = null
        boolean flagAfter = false
        def thread = Thread.ofPlatform().start {
            answered = git.run(CWD, 'fetch', 'origin')
            flagAfter = Thread.currentThread().isInterrupted()
        }

        when:
        git.awaitCall(['fetch'], thread)
        thread.interrupt()
        thread.join(5_000)

        then:
        !thread.alive
        answered.termination() == Termination.INTERRUPTED
        flagAfter
    }

    private static List result(GitCommandResult result) {
        [
            result.exitCode(),
            result.stdout().forParsing(),
            result.stderr().forParsing()
        ]
    }
}
