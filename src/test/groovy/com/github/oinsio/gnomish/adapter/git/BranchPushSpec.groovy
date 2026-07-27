package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Path
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR15, D2 of add-tracker-port: revocation's best-effort push, distinct from {@link
 * BestEffortPush}'s round-boundary-gated push — no {@code RoundBoundaryCheck}/{@code
 * previousTip} preconditions apply, since revocation is not a round. Same refspec convention and
 * failure discipline: exact {@code origin branch:branch}, never {@code --force}, no-origin is a
 * silent no-op, a failed push WARNs and never throws.
 */
class BranchPushSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path repo
    Path bareRepo
    BranchPush push

    def setup() {
        repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'first'
        runner.run(repo, 'add', 'a.txt')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        runner.run(repo, 'checkout', '-q', '-b', 'gnomish/PROJ-1')

        bareRepo = initBareRepo(tempDir, 'origin.git')
        runner.run(repo, 'remote', 'add', 'origin', bareRepo.toString())

        push = new BranchPush(runner)
    }

    private String currentHead() {
        runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
    }

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(BranchPush)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    def "with no origin remote configured, pushBestEffort is a silent no-op"() {
        given: 'a repo with no origin remote configured at all'
        def noOriginRepo = tempDir.resolve('no-origin-repo')
        def noOriginRunner = new GitProcessRunner()
        noOriginRunner.run(tempDir, 'init', noOriginRepo.toString())
        new File(noOriginRepo.toFile(), 'a.txt').text = 'first'
        noOriginRunner.run(noOriginRepo, 'add', 'a.txt')
        noOriginRunner.run(noOriginRepo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        noOriginRunner.run(noOriginRepo, 'checkout', '-q', '-b', 'gnomish/PROJ-99')
        assert noOriginRunner.run(noOriginRepo, 'remote', 'get-url', 'origin').exitCode() != 0
        def noOriginPush = new BranchPush(noOriginRunner)

        when:
        def events = capture {
            noOriginPush.pushBestEffort(noOriginRepo, 'gnomish/PROJ-99')
        }

        then:
        noExceptionThrown()
        events.isEmpty()
    }

    def "a normal push updates the remote's task branch to the exact same-named ref"() {
        when:
        push.pushBestEffort(repo, 'gnomish/PROJ-1')

        then:
        def remoteHead = runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim()
        remoteHead == currentHead()
    }

    def "a non-fast-forward push rejection just WARNs, no force retry"() {
        given: 'the remote already has a commit the local branch does not, so the push is rejected'
        def otherClone = tempDir.resolve('other-clone')
        runner.run(tempDir, 'clone', '-q', bareRepo.toString(), otherClone.toString())
        runner.run(otherClone, 'checkout', '-q', '-b', 'gnomish/PROJ-1')
        new File(otherClone.toFile(), 'divergent.txt').text = 'pushed by someone else first'
        runner.run(otherClone, 'add', 'divergent.txt')
        runner.run(otherClone, '-c', 'user.email=x@b.c', '-c', 'user.name=x', 'commit', '-m', 'divergent')
        runner.run(otherClone, 'push', 'origin', 'gnomish/PROJ-1:gnomish/PROJ-1')
        def remoteHeadBeforeLocalPush = runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim()

        when:
        def events = capture {
            push.pushBestEffort(repo, 'gnomish/PROJ-1')
        }

        then:
        noExceptionThrown()
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim() == remoteHeadBeforeLocalPush

        and: 'a WARN was actually logged for the rejected push'
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith('revocation push failed:')
        events[0].formattedMessage.contains('branch=gnomish/PROJ-1')
    }
}
