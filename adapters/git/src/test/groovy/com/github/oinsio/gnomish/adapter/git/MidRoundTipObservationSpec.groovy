package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.time.MovableClock
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of harden-logging-observability, "A failed poll observation is not a change" scenario of
 * the git-task-persistence delta: the mid-round poll may skip an observation, but must never read
 * a failed tip resolution as one. The empty string differs from every real SHA, so an unverified
 * read reports movement on the poll that fails and a return to the old tip on the next — decisions
 * made on a read that established nothing.
 */
class MidRoundTipObservationSpec extends Specification implements BareGitRepoFixture, FailingSubcommandGitFixture {

    static final String BRANCH = 'gnomish/PROJ-9'

    @TempDir
    Path tempDir

    def toolEvent = new AgentProgressEvent.ToolStarted('Bash')
    Instant now = Instant.parse('2026-08-08T10:00:00Z')
    def clock = { -> now } as Clock
    MovableClock suppressorClock = new MovableClock(now)
    RepeatSuppressor suppressor = new RepeatSuppressor(suppressorClock, RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL)

    Path clone
    Path origin

    def setup() {
        clone = initWorkingRepo(tempDir, 'factory-clone')
        new File(clone.toFile(), 'a.txt').text = 'first'
        commitAll(clone)
        gitOutput(clone, 'branch', BRANCH)
        origin = initBareRepo(tempDir, 'origin.git')
        addRemote(clone, 'origin', origin.toString())
    }

    /** Advances the (never checked-out) task branch by one plumbing commit, as a harvest would. */
    private void advanceBranch() {
        def tree = gitOutput(clone, 'rev-parse', 'HEAD^{tree}')
        def parent = gitOutput(clone, 'rev-parse', 'refs/heads/' + BRANCH)
        def commit = gitOutput(
                clone, '-c', 'user.email=g@b.c', '-c', 'user.name=g',
                'commit-tree', tree, '-p', parent, '-m', 'in-box commit')
        gitOutput(clone, 'update-ref', 'refs/heads/' + BRANCH, commit)
    }

    private MidRoundHarvestListener blindListener() {
        def env = [harvest: { advanceBranch() }] as TaskExecutionEnvironment
        new MidRoundHarvestListener(
                env,
                new GitProcessRunner(gitFailingOn(tempDir, 'rev-parse').toString()),
                clone,
                'PROJ-9',
                BRANCH,
                clock,
                Duration.ofSeconds(30),
                suppressor)
    }

    def "FR13: a tip the poll cannot resolve changes no harvest decision"() {
        given: 'a round whose tip reads all fail, over a branch that really did move'
        def logs = LogCaptureSupport.attach(MidRoundHarvestListener, Level.DEBUG)
        def listener = blindListener()

        when:
        listener.onProgress(toolEvent)

        then: 'nothing was pushed — a failed read is not movement'
        runner().run(origin, 'rev-parse', '--verify', 'refs/heads/' + BRANCH).exitCode() != 0

        and: 'the skip is announced once, naming the subject and the git evidence'
        def warnings = logs.list.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('mid-round tip resolution skipped')
        warnings[0].formattedMessage.contains('taskId=PROJ-9')
        warnings[0].formattedMessage.contains(GIT_FAILURE_STDERR)

        cleanup:
        logs.detach()
    }

    def "FR13: repeated unresolvable tips are suppressed, not one WARN per progress event"() {
        given:
        def logs = LogCaptureSupport.attach(MidRoundHarvestListener, Level.DEBUG)
        def listener = blindListener()

        when: 'four polls of the round, each a minute apart'
        4.times {
            listener.onProgress(toolEvent)
            now = now.plusSeconds(60)
            suppressorClock.advance(Duration.ofMinutes(1))
        }

        then: 'one WARN for the streak, the rest diagnosis-only with the running count'
        logs.list.findAll { it.level == Level.WARN }.size() == 1
        def repeats = logs.list.findAll { it.level == Level.DEBUG }
        repeats.size() == 3
        repeats.last().formattedMessage.contains('4x')

        cleanup:
        logs.detach()
    }

    def "FR4, FR13: a tip that resolves again ends the outage with one recovery line"() {
        given:
        def logs = LogCaptureSupport.attach(MidRoundHarvestListener, Level.DEBUG)
        def listener = blindListener()

        and: 'two polls of the round while the resolution is failing'
        2.times {
            listener.onProgress(toolEvent)
            now = now.plusSeconds(60)
            suppressorClock.advance(Duration.ofMinutes(1))
        }

        when: 'git starts answering again, and the next poll resolves the tip'
        healGit(tempDir, 'rev-parse')
        listener.onProgress(toolEvent)

        then: 'the last word on the subject is the recovery, with the outage it covered'
        def recoveries = logs.list.findAll { it.level == Level.INFO }
        recoveries.size() == 1
        recoveries[0].formattedMessage.contains('mid-round tip resolution recovered')
        recoveries[0].formattedMessage.contains('2 failure(s)')
        recoveries[0].formattedMessage.contains('taskId=PROJ-9')

        and: 'the branch that moved while the poll was blind is pushed once it can be seen'
        gitOutput(origin, 'rev-parse', 'refs/heads/' + BRANCH) == gitOutput(clone, 'rev-parse', 'refs/heads/' + BRANCH)

        cleanup:
        logs.detach()
    }

    private static GitProcessRunner runner() {
        new GitProcessRunner()
    }
}
