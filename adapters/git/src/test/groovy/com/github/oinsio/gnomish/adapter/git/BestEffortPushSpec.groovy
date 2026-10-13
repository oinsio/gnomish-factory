package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-S1 of add-git-workflow: push is the adapter's exclusive responsibility — exact refspec
 * {@code origin gnomish/<task>:gnomish/<task>}, never {@code --force}, and a push only runs once
 * the round-boundary preconditions (still on the task branch, previousTip is an ancestor of HEAD)
 * are freshly confirmed via {@link HostBranchProbe}'s non-throwing queries. The closing push carries
 * the branch and the register the round's own transaction moved together (design D13 of
 * tighten-spec-contract); this spec registers by hand what it commits by hand. Any failure here —
 * a broken precondition or a rejected push (e.g. non-fast-forward) — is a skip-or-WARN, never a
 * thrown exception and never a forced retry.
 *
 * <p>{@link GitAttemptPersistenceSpec} already covers the happy-path push-succeeds and
 * push-fails-cleanly (no-origin, unreachable-origin) scenarios end to end through {@code persist}.
 * This spec drives {@link BestEffortPush} directly so the two new precondition-skip paths can be
 * set up without needing a full {@code GitAttemptPersistence} round trip.
 */
class BestEffortPushSpec extends Specification implements BareGitRepoFixture, RegisteredBranchFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path repo
    Path bareRepo
    BestEffortPush push

    def setup() {
        repo = initTaskWorkingRepo(tempDir)

        bareRepo = initBareRepo(tempDir, 'origin.git')
        addRemote(repo, 'origin', bareRepo.toString())

        push = BestEffortPush.closing(runner)
    }

    /** Migrated to the shared helper (`.claude/rules/logging.md`) when this spec was next touched. */
    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        def logs = LogCaptureSupport.attach(BestEffortPush)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    // FR11: with no `origin` remote configured at all, pushBestEffort is a silent no-op — proven
    // against a repo that never had `origin remote add` run (unlike every other scenario in this
    // spec, all of which configure origin in setup()).
    def "FR11: with no origin remote configured, pushBestEffort is a silent no-op"() {
        given: 'a repo with no origin remote configured at all'
        def bareRepoDir = tempDir.resolve('no-origin-repo')
        def noOriginRunner = new GitProcessRunner()
        noOriginRunner.run(tempDir, 'init', bareRepoDir.toString())
        new File(bareRepoDir.toFile(), 'a.txt').text = 'first'
        noOriginRunner.run(bareRepoDir, 'add', 'a.txt')
        noOriginRunner.run(bareRepoDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        noOriginRunner.run(bareRepoDir, 'checkout', '-q', '-b', 'gnomish/PROJ-99')
        assert noOriginRunner.run(bareRepoDir, 'remote', 'get-url', 'origin').exitCode() != 0

        def check = new HostBranchProbe(noOriginRunner, bareRepoDir, 'gnomish/PROJ-99')
        def previousTip = noOriginRunner.run(bareRepoDir, 'rev-parse', 'HEAD').stdout().forParsing().trim()
        def noOriginPush = BestEffortPush.closing(noOriginRunner)

        when:
        def events = capture {
            noOriginPush.pushBestEffort('PROJ-99', 'implement', 0, bareRepoDir, 'gnomish/PROJ-99', check, previousTip)
        }

        then: 'no exception, and FR11 "no warnings" — a genuine no-op, not a push that happened to succeed silently'
        noExceptionThrown()
        events.isEmpty()
    }

    def "NFR-S1: a normal push updates the remote's task branch to the exact same-named ref"() {
        given: 'the round commit landed with its register, as its closing transaction lands it'
        def check = new HostBranchProbe(runner, repo, 'gnomish/PROJ-1')
        def previousTip = currentHead(repo)
        registerTip(repo, 'PROJ-1')

        when:
        push.pushBestEffort('PROJ-1', 'implement', 0, repo, 'gnomish/PROJ-1', check, previousTip)

        then:
        def remoteHead = runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().forParsing().trim()
        remoteHead == currentHead(repo)

        and: 'FR13 of tighten-spec-contract: the round close is a closing push — the register travels with the branch'
        runner.run(bareRepo, 'rev-parse', 'refs/gnomish-factory/PROJ-1').stdout().forParsing().trim() == remoteHead
        runner.run(repo, 'rev-parse', 'refs/gnomish-factory/PROJ-1').stdout().forParsing().trim() == remoteHead
    }

    // Scenario: git/git-task-persistence / A mid-round push leaves the register
    def "FR13: the mid-round push moves the branch alone, the register stays on the last closing commit"() {
        given: 'a closing push registered the current tip, then the gnome committed above it'
        def check = new HostBranchProbe(runner, repo, 'gnomish/PROJ-1')
        def closed = currentHead(repo)
        registerTip(repo, 'PROJ-1')
        push.pushBestEffort('PROJ-1', 'implement', 0, repo, 'gnomish/PROJ-1', check, closed)
        commit(repo, 'gnome.txt', 'mid-round work')

        when:
        BestEffortPush.midRound(runner).pushBestEffort('PROJ-1', 'implement', 1, repo, 'gnomish/PROJ-1', check, closed)

        then: 'origin carries the gnome commit on the branch, and the register in origin and in the clone did not move'
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().forParsing().trim() == currentHead(repo)
        runner.run(bareRepo, 'rev-parse', 'refs/gnomish-factory/PROJ-1').stdout().forParsing().trim() == closed
        runner.run(repo, 'rev-parse', 'refs/gnomish-factory/PROJ-1').stdout().forParsing().trim() == closed
    }

    def "NFR-S1: push is skipped with no exception when HEAD is off the task branch"() {
        given:
        def check = new HostBranchProbe(runner, repo, 'gnomish/PROJ-1')
        def previousTip = currentHead(repo)
        runner.run(repo, 'checkout', '-q', '-b', 'not-the-task-branch')

        when:
        def events = capture {
            push.pushBestEffort('PROJ-1', 'implement', 0, repo, 'gnomish/PROJ-1', check, previousTip)
        }

        then:
        noExceptionThrown()
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').exitCode() != 0

        and: 'NFR-O2: exactly one WARN, carrying taskId/stage/round/branch context'
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith(OperatorEvent.PUSH_SKIPPED_HEAD_OFF_BRANCH.head())
        events[0].formattedMessage.contains('taskId=PROJ-1')
        events[0].formattedMessage.contains('stage=implement')
        events[0].formattedMessage.contains('round=0')
        events[0].formattedMessage.contains('branch=gnomish/PROJ-1')
    }

    def "NFR-S1: push is skipped with no exception when previousTip is not an ancestor of HEAD"() {
        given: 'an orphan commit replaces the branch history, so the remembered previousTip is stranded'
        def check = new HostBranchProbe(runner, repo, 'gnomish/PROJ-1')
        def previousTip = currentHead(repo)
        runner.run(repo, 'checkout', '-q', '--orphan', 'rewritten-history')
        new File(repo.toFile(), 'rewritten.txt').text = 'rewritten history'
        runner.run(repo, 'add', 'rewritten.txt')
        runner.run(repo, '-c', 'user.email=g@b.c', '-c', 'user.name=g', 'commit', '-m', 'orphan root')
        runner.run(repo, 'branch', '-f', 'gnomish/PROJ-1', 'rewritten-history')
        runner.run(repo, 'checkout', '-q', 'gnomish/PROJ-1')

        when:
        def events = capture {
            push.pushBestEffort('PROJ-1', 'implement', 0, repo, 'gnomish/PROJ-1', check, previousTip)
        }

        then:
        noExceptionThrown()
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').exitCode() != 0

        and: 'NFR-O2: exactly one WARN, carrying taskId/stage/round/branch/previousTip context'
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith(OperatorEvent.PUSH_SKIPPED_TIP_NOT_ANCESTOR.head())
        events[0].formattedMessage.contains('taskId=PROJ-1')
        events[0].formattedMessage.contains('stage=implement')
        events[0].formattedMessage.contains('round=0')
        events[0].formattedMessage.contains('branch=gnomish/PROJ-1')
        events[0].formattedMessage.contains(previousTip)
    }

    // Scenario: git/git-task-persistence / Non-fast-forward never escalates to force
    def "NFR-S1: a non-fast-forward push rejection just WARNs, no force retry"() {
        given: 'the remote already has a commit the local branch does not, so the push is rejected'
        def otherClone = tempDir.resolve('other-clone')
        seedClone(tempDir, bareRepo.toString(), otherClone, '-q')
        runner.run(otherClone, 'checkout', '-q', '-b', 'gnomish/PROJ-1')
        new File(otherClone.toFile(), 'divergent.txt').text = 'pushed by someone else first'
        runner.run(otherClone, 'add', 'divergent.txt')
        runner.run(otherClone, '-c', 'user.email=x@b.c', '-c', 'user.name=x', 'commit', '-m', 'divergent')
        runner.run(otherClone, 'push', 'origin', 'gnomish/PROJ-1:gnomish/PROJ-1')
        def remoteHeadBeforeLocalPush = runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().forParsing().trim()

        def check = new HostBranchProbe(runner, repo, 'gnomish/PROJ-1')
        def previousTip = currentHead(repo)
        registerTip(repo, 'PROJ-1')

        when:
        def events = capture {
            push.pushBestEffort('PROJ-1', 'implement', 0, repo, 'gnomish/PROJ-1', check, previousTip)
        }

        then:
        noExceptionThrown()
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().forParsing().trim() == remoteHeadBeforeLocalPush

        and: 'a WARN was actually logged for the rejected push — proving the failure branch, not the success one, ran'
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith(OperatorEvent.PUSH_FAILED.head() + 'push failed:')
        events[0].formattedMessage.contains('taskId=PROJ-1')
        events[0].formattedMessage.contains('stage=implement')
        events[0].formattedMessage.contains('round=0')
        events[0].formattedMessage.contains('branch=gnomish/PROJ-1')
    }
}
