package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5 of add-sandbox-core; FR13 of tighten-spec-contract (design D18): the mid-round admission
 * pushes what it admitted — the task branch alone, best-effort — with no host precondition, since
 * the admission is the precondition. Same refspec convention and failure discipline as every push
 * point: exact {@code origin branch:branch}, never {@code --force}, no-origin is a silent no-op, a
 * failed push WARNs and never throws.
 */
class HarvestAdmissionPushSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path repo
    Path bareRepo
    HarvestAdmission push

    def setup() {
        repo = initTaskWorkingRepo(tempDir)

        bareRepo = initBareRepo(tempDir, 'origin.git')
        addRemote(repo, 'origin', bareRepo.toString())

        push = new HarvestAdmission(runner, repo)
    }

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        def logs = LogCaptureSupport.attach(HarvestAdmission)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    def "FR5: with no origin remote configured, the admitted push is a silent no-op"() {
        given: 'a repo with no origin remote configured at all'
        def noOriginRepo = tempDir.resolve('no-origin-repo')
        def noOriginRunner = new GitProcessRunner()
        noOriginRunner.run(tempDir, 'init', noOriginRepo.toString())
        new File(noOriginRepo.toFile(), 'a.txt').text = 'first'
        noOriginRunner.run(noOriginRepo, 'add', 'a.txt')
        noOriginRunner.run(noOriginRepo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        noOriginRunner.run(noOriginRepo, 'checkout', '-q', '-b', 'gnomish/PROJ-99')
        assert noOriginRunner.run(noOriginRepo, 'remote', 'get-url', 'origin').exitCode() != 0
        def noOriginPush = new HarvestAdmission(noOriginRunner, noOriginRepo)

        when:
        def events = capture {
            noOriginPush.pushAdmitted('PROJ-99')
        }

        then:
        noExceptionThrown()
        events.isEmpty()
    }

    // Scenario: git/git-task-persistence / A mid-round push leaves the register
    def "FR13: the admitted push updates the remote's task branch alone, never the register"() {
        when:
        push.pushAdmitted('PROJ-1')

        then:
        def remoteHead = runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().forParsing().trim()
        remoteHead == currentHead(repo)
        runner.run(bareRepo, 'rev-parse', '--verify', '--quiet', 'refs/gnomish-factory/PROJ-1').exitCode() != 0
    }

    // Scenario: git/git-task-persistence / Non-fast-forward never escalates to force
    def "a non-fast-forward push rejection just WARNs, no force retry"() {
        given: 'the remote already has a commit the local branch does not, so the push is rejected'
        def otherClone = tempDir.resolve('other-clone')
        seedClone(tempDir, bareRepo.toString(), otherClone, '-q')
        runner.run(otherClone, 'checkout', '-q', '-b', 'gnomish/PROJ-1')
        new File(otherClone.toFile(), 'divergent.txt').text = 'pushed by someone else first'
        runner.run(otherClone, 'add', 'divergent.txt')
        runner.run(otherClone, '-c', 'user.email=x@b.c', '-c', 'user.name=x', 'commit', '-m', 'divergent')
        runner.run(otherClone, 'push', 'origin', 'gnomish/PROJ-1:gnomish/PROJ-1')
        def remoteHeadBeforeLocalPush = runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().forParsing().trim()

        when:
        def events = capture {
            push.pushAdmitted('PROJ-1')
        }

        then:
        noExceptionThrown()
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().forParsing().trim() == remoteHeadBeforeLocalPush

        and: 'a WARN was actually logged for the rejected push'
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith(OperatorEvent.BRANCH_PUSH_FAILED.head() + 'mid-round push failed:')
        events[0].formattedMessage.contains('branch=gnomish/PROJ-1')

        and: 'FR6 of harden-logging-observability: git rejects a push in several lines of prose, ' +
        'and all of it reaches the log as one line — a subprocess stream is untrusted text ' +
        'whose newlines would otherwise become log lines of their own'
        !events[0].formattedMessage.contains('\n')
        !events[0].formattedMessage.contains('\r')

        and: 'and the rejection is still legible after neutralization'
        events[0].formattedMessage.contains('rejected') || events[0].formattedMessage.contains('non-fast-forward')
    }
}
