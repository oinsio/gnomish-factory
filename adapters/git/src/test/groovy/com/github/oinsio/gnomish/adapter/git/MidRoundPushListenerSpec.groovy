package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.time.MovableClock
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11 of add-git-workflow ("the adapter also notices gnome commits mid-round (tip moved) and
 * pushes best-effort") and the "Gnome commit triggers a push" scenario of the
 * git-task-persistence delta spec: {@link MidRoundPushListener} is the agent-cli live loop's
 * seam for this — an {@link com.github.oinsio.gnomish.app.port.agent.AgentProgressListener} that
 * notices {@code HEAD} moving between two delivered progress events and delegates to {@link
 * BestEffortPush} using the exact same round-boundary preconditions as the post-round push
 * (task 3.8), never reimplementing them.
 */
class MidRoundPushListenerSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path repo
    Path bareRepo

    def toolEvent = new AgentProgressEvent.ToolStarted('Bash')

    def suppressor = new RepeatSuppressor(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMinutes(5))

    def setup() {
        repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'first'
        runner.run(repo, 'add', 'a.txt')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        runner.run(repo, 'checkout', '-q', '-b', 'gnomish/PROJ-1')

        bareRepo = initBareRepo(tempDir, 'origin.git')
        runner.run(repo, 'remote', 'add', 'origin', bareRepo.toString())
    }

    private String currentHead() {
        runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
    }

    private void gnomeCommit(String fileName = 'gnome.txt', String content = 'gnome work') {
        new File(repo.toFile(), fileName).text = content
        runner.run(repo, 'add', fileName)
        runner.run(repo, '-c', 'user.email=g@b.c', '-c', 'user.name=g', 'commit', '-m', 'gnome commit')
    }

    def "FR11: no push when HEAD has not moved since construction"() {
        given:
        def listener = new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', suppressor))

        when:
        listener.onProgress(toolEvent)

        then:
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').exitCode() != 0
    }

    def "FR11: a gnome commit mid-round triggers a best-effort push on the next event"() {
        given:
        def listener = new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', suppressor))
        gnomeCommit()

        when:
        listener.onProgress(toolEvent)

        then:
        def remoteHead = runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim()
        remoteHead == currentHead()
    }

    def "FR11: a second event with an unchanged tip does not push again"() {
        given:
        def listener = new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', suppressor))
        gnomeCommit()
        listener.onProgress(toolEvent)
        def firstPushedHead = runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim()

        when: 'the remote branch is force-updated out of band, so a repeated push would be observable'
        runner.run(repo, 'checkout', '-q', '-b', 'scratch')
        runner.run(repo, 'checkout', '-q', 'gnomish/PROJ-1')
        listener.onProgress(toolEvent)

        then: 'no additional push attempt happened - the remote tip is unchanged'
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim() == firstPushedHead
    }

    def "NFR-S1: push is skipped when HEAD moved but is off the expected branch"() {
        given:
        def listener = new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', suppressor))
        runner.run(repo, 'checkout', '-q', '-b', 'not-the-task-branch')
        gnomeCommit()

        when:
        listener.onProgress(toolEvent)

        then:
        noExceptionThrown()
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').exitCode() != 0
    }

    def "NFR-S1: push is skipped when the observed baseline is no longer an ancestor of HEAD"() {
        given: 'an orphan commit replaces branch history after construction, stranding the baseline tip'
        def listener = new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', suppressor))
        runner.run(repo, 'checkout', '-q', '--orphan', 'rewritten-history')
        new File(repo.toFile(), 'rewritten.txt').text = 'rewritten history'
        runner.run(repo, 'add', 'rewritten.txt')
        runner.run(repo, '-c', 'user.email=g@b.c', '-c', 'user.name=g', 'commit', '-m', 'orphan root')
        runner.run(repo, 'branch', '-f', 'gnomish/PROJ-1', 'rewritten-history')
        runner.run(repo, 'checkout', '-q', 'gnomish/PROJ-1')

        when:
        listener.onProgress(toolEvent)

        then:
        noExceptionThrown()
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').exitCode() != 0
    }

    def "FR11: no remote configured means onProgress never throws even after a tip movement"() {
        given:
        runner.run(repo, 'remote', 'remove', 'origin')
        def listener = new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', suppressor))
        gnomeCommit()

        when:
        listener.onProgress(toolEvent)

        then:
        noExceptionThrown()
    }

    def "FR13: an unresolvable HEAD at construction is an unknown baseline, not a thrown failure"() {
        given: 'a worktree whose HEAD points at an unborn branch, so rev-parse HEAD cannot answer'
        runner.run(repo, 'checkout', '-q', '--orphan', 'unborn')

        when:
        new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', suppressor))

        then:
        noExceptionThrown()
    }

    def "FR13: an unresolvable HEAD mid-round skips the observation instead of throwing"() {
        given:
        def listener = new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', suppressor))

        when: 'HEAD stops resolving between two progress events'
        runner.run(repo, 'checkout', '-q', '--orphan', 'unborn')
        listener.onProgress(toolEvent)

        then: 'the listener contract holds and nothing was pushed on the strength of a blank read'
        noExceptionThrown()
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').exitCode() != 0
    }

    def "FR13: an unknown baseline is adopted by the first resolving event, and only later movement pushes"() {
        given: 'the round starts with no resolvable HEAD, so the listener has no ancestry baseline'
        runner.run(repo, 'checkout', '-q', '--orphan', 'unborn')
        def listener = new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', suppressor))

        when: 'HEAD becomes resolvable again on the task branch and an event adopts it'
        runner.run(repo, 'checkout', '-q', '-f', 'gnomish/PROJ-1')
        listener.onProgress(toolEvent)

        then: 'the adoption alone pushes nothing - there was no observed tip to prove ancestry from'
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').exitCode() != 0

        when: 'the gnome then commits and a further event notices the movement'
        gnomeCommit()
        listener.onProgress(toolEvent)

        then: 'the movement away from the adopted baseline is pushed'
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim() == currentHead()
    }

    def "FR4: a tip resolution that works again closes the streak with one INFO"() {
        given:
        def listener = new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', suppressor))
        def logs = LogCaptureSupport.attach(MidRoundPushListener, Level.INFO)

        when: 'HEAD stops resolving, then resolves again on a later event'
        runner.run(repo, 'checkout', '-q', '--orphan', 'unborn')
        listener.onProgress(toolEvent)
        runner.run(repo, 'checkout', '-q', '-f', 'gnomish/PROJ-1')
        listener.onProgress(toolEvent)

        then: 'the last word on the subject is the recovery, not the failure'
        def recovery = logs.list.find { it.level == Level.INFO }
        recovery != null
        recovery.formattedMessage.contains('mid-round tip resolution recovered')
        recovery.formattedMessage.contains('taskId=PROJ-1')
    }

    def "FR4, FR13: a failing tip resolution is one WARN, then counted rather than repeated"() {
        given:
        def listener = new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', suppressor))
        def logs = LogCaptureSupport.attach(MidRoundPushListener, Level.DEBUG)

        when: 'HEAD stays unresolvable across a burst of progress events'
        runner.run(repo, 'checkout', '-q', '--orphan', 'unborn')
        5.times { listener.onProgress(toolEvent) }

        then: 'the first occurrence warns and the rest are DEBUG repeats'
        logs.list.findAll { it.level == Level.WARN }.size() == 1
        logs.list.findAll { it.level == Level.DEBUG }.size() == 4

        and: 'the WARN names the task, the branch and git\'s own evidence'
        def warning = logs.list.find { it.level == Level.WARN }.formattedMessage
        warning.startsWith(OperatorEvent.MID_ROUND_POLL_SKIPPED.head())
        warning.contains('taskId=PROJ-1')
        warning.contains('branch=gnomish/PROJ-1')
        warning.contains('git rev-parse')
    }

    // FR15 of harden-logging-observability: the host twin's roll-up edge, the one the audit found
    // dark. A streak that outlives the quiet period must reach the console again with its count —
    // otherwise a tip that has been unresolvable for the whole round says so exactly once and then
    // goes quiet, which reads like a recovery.
    def "FR15: a tip resolution still failing past the roll-up interval is announced again, with its count"() {
        given: 'a suppressor on movable time, so the quiet period can elapse without waiting'
        def suppressorClock = new MovableClock(Instant.EPOCH)
        def rollingSuppressor = new RepeatSuppressor(suppressorClock, Duration.ofMinutes(5))
        def listener = new MidRoundPushListener(runner, repo, 'implement', 0, new MidRoundPollContext('PROJ-1', 'gnomish/PROJ-1', rollingSuppressor))
        def logs = LogCaptureSupport.attach(MidRoundPushListener, Level.DEBUG)

        when: 'HEAD stays unresolvable across a burst, then one more event past the quiet period'
        runner.run(repo, 'checkout', '-q', '--orphan', 'unborn')
        3.times { listener.onProgress(toolEvent) }
        suppressorClock.advance(Duration.ofMinutes(6))
        listener.onProgress(toolEvent)

        then:
        def rollUps = logs.list.findAll {
            it.formattedMessage.startsWith(OperatorEvent.MID_ROUND_POLL_SKIPPED_ROLLUP.head())
        }
        rollUps.size() == 1
        rollUps[0].level == Level.WARN
        rollUps[0].formattedMessage.contains('4x')
        rollUps[0].formattedMessage.contains('taskId=PROJ-1')

        cleanup:
        logs.detach()
    }
}
