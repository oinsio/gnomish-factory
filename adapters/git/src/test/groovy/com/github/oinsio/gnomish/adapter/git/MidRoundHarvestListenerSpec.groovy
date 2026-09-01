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
 * FR5 of add-sandbox-core, "Mid-round tip movement in a box is pushed"
 * scenario of the git-task-persistence delta: {@link MidRoundHarvestListener}
 * polls the environment rate-limited on agent progress events — harvest, then
 * a best-effort push when the harvested tip moved. A refused harvest skips the
 * push with a WARN and never throws; the rate limit caps how often a
 * commit-spamming gnome can cause a fetch at all. The environment is faked (a
 * closure that advances the branch, simulating a harvested in-box commit); the
 * factory clone and origin are real local repos.
 *
 * <p>A harvest that keeps failing must not cost one WARN per progress event: failures go through a
 * {@link RepeatSuppressor} and this asserts the edges it produces (FR4, UX3 of
 * harden-logging-observability).
 */
class MidRoundHarvestListenerSpec extends Specification implements BareGitRepoFixture {

    static final String BRANCH = 'gnomish/PROJ-9'

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def toolEvent = new AgentProgressEvent.ToolStarted('Bash')

    Path clone
    Path origin
    Instant now = Instant.parse('2026-08-08T10:00:00Z')
    def clock = { -> now } as Clock

    /** The suppressor's own time source: virtual, so the roll-up interval elapses instantly. */
    MovableClock suppressorClock = new MovableClock(now)

    RepeatSuppressor suppressor = new RepeatSuppressor(suppressorClock, RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL)

    def setup() {
        clone = initWorkingRepo(tempDir, 'factory-clone')
        new File(clone.toFile(), 'a.txt').text = 'first'
        commitAll(clone)
        gitOutput(clone, 'branch', BRANCH)
        origin = initBareRepo(tempDir, 'origin.git')
        addRemote(clone, 'origin', origin.toString())
    }

    private static TaskExecutionEnvironment environment(Closure onHarvest) {
        [harvest: onHarvest] as TaskExecutionEnvironment
    }

    /** Advances the (never checked-out) task branch by one plumbing commit, as a harvest would. */
    private String advanceBranch() {
        def tree = gitOutput(clone, 'rev-parse', 'HEAD^{tree}')
        def parent = gitOutput(clone, 'rev-parse', 'refs/heads/' + BRANCH)
        def commit = gitOutput(
                clone, '-c', 'user.email=g@b.c', '-c', 'user.name=g',
                'commit-tree', tree, '-p', parent, '-m', 'in-box commit')
        gitOutput(clone, 'update-ref', 'refs/heads/' + BRANCH, commit)
        commit
    }

    private MidRoundHarvestListener listener(TaskExecutionEnvironment env, Duration interval = Duration.ofSeconds(30)) {
        new MidRoundHarvestListener(env, runner, clone, 'PROJ-9', BRANCH, clock, interval, suppressor)
    }

    def "FR5: a moved harvested tip is pushed best-effort to origin"() {
        given: 'an environment whose harvest lands one new commit on the branch'
        String pushed = null
        def l = listener(environment({ pushed = advanceBranch() }))

        when:
        l.onProgress(toolEvent)

        then: 'origin now has the branch at the harvested tip'
        gitOutput(origin, 'rev-parse', 'refs/heads/' + BRANCH) == pushed
    }

    def "FR5: an unchanged harvested tip pushes nothing"() {
        given:
        def l = listener(environment({ }))

        when:
        l.onProgress(toolEvent)

        then: 'origin never saw the branch'
        runner.run(origin, 'rev-parse', '--verify', 'refs/heads/' + BRANCH).exitCode() != 0
    }

    def "FR5: polls inside the rate-limit window are skipped — the box cannot cause a fetch storm"() {
        given:
        def harvests = 0
        def l = listener(environment({ harvests++ }), Duration.ofSeconds(30))

        when: 'three events land within the interval'
        l.onProgress(toolEvent)
        now = now.plusSeconds(5)
        l.onProgress(toolEvent)
        now = now.plusSeconds(5)
        l.onProgress(toolEvent)

        then: 'only the first polled'
        harvests == 1

        when: 'the interval elapses'
        now = now.plusSeconds(30)
        l.onProgress(toolEvent)

        then:
        harvests == 2
    }

    def "FR5: a poll landing exactly at the rate-limit boundary is allowed, not skipped"() {
        given:
        def harvests = 0
        def l = listener(environment({ harvests++ }), Duration.ofSeconds(30))

        when: 'the second event lands exactly minInterval after the first poll'
        l.onProgress(toolEvent)
        now = now.plusSeconds(30)
        l.onProgress(toolEvent)

        then: 'elapsed == minInterval already satisfies the rate limit'
        harvests == 2
    }

    def "FR5: a refused harvest skips the push and never throws — the round boundary keeps the verdict"() {
        given:
        def l = listener(environment({
            advanceBranch()
            throw new HarvestRefusedException(BRANCH, 'non-fast-forward')
        }))

        when:
        l.onProgress(toolEvent)

        then: 'no exception escapes the listener and nothing was pushed'
        noExceptionThrown()
        runner.run(origin, 'rev-parse', '--verify', 'refs/heads/' + BRANCH).exitCode() != 0
    }

    def "FR4, UX3: an environment that cannot be harvested all round is announced once, then counted"() {
        given: 'a harvest that fails on every poll of the round'
        def l = listener(environment({
            throw new HarvestRefusedException(BRANCH, 'non-fast-forward')
        }))
        def logs = LogCaptureSupport.attach(MidRoundHarvestListener, Level.DEBUG)

        when: 'five polls, each a minute apart — inside the roll-up interval'
        5.times {
            l.onProgress(toolEvent)
            now = now.plusSeconds(60)
            suppressorClock.advance(Duration.ofMinutes(1))
        }

        then: 'the console carries the fault once, with the throwable on it'
        def warnings = logs.list.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('taskId=PROJ-9')
        warnings[0].throwableProxy.className == HarvestRefusedException.name

        and: 'the other four polls are diagnosis-only and carry the running count'
        def repeats = logs.list.findAll { it.level == Level.DEBUG }
        repeats.size() == 4
        repeats.last().formattedMessage.contains('5x')

        cleanup:
        logs.detach()
    }

    // FR4: one suppressor serves every round of the process, so the streak key must namespace by
    // branch — otherwise a second task's very first failed harvest reads as a repeat of a streak
    // belonging to a round it has nothing to do with, and never reaches the console at all.
    def "FR4: two rounds on different branches keep separate streaks"() {
        given: 'a second task branch, and a harvest that fails for both'
        def otherBranch = 'gnomish/PROJ-10'
        gitOutput(clone, 'branch', otherBranch)
        def failing = environment({
            throw new HarvestRefusedException(BRANCH, 'non-fast-forward')
        })

        and: 'the first round has already spent a failure'
        listener(failing).onProgress(toolEvent)
        now = now.plusSeconds(60)
        suppressorClock.advance(Duration.ofMinutes(1))

        when: 'an unrelated round on the other branch fails for the first time'
        def logs = LogCaptureSupport.attach(MidRoundHarvestListener, Level.DEBUG)
        new MidRoundHarvestListener(
                failing, runner, clone, 'PROJ-10', otherBranch, clock, Duration.ofSeconds(30), suppressor)
                .onProgress(toolEvent)
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'it is that round\'s own first occurrence — a WARN, not someone else\'s repeat'
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('taskId=PROJ-10')
    }

    def "FR4: a harvest that works again announces the recovery and its outage"() {
        given: 'a harvest that refuses once, then succeeds'
        def refuse = true
        def l = listener(environment({
            if (refuse) {
                throw new HarvestRefusedException(BRANCH, 'non-fast-forward')
            }
        }))
        def logs = LogCaptureSupport.attach(MidRoundHarvestListener)

        when:
        l.onProgress(toolEvent)
        refuse = false
        now = now.plusSeconds(120)
        suppressorClock.advance(Duration.ofMinutes(2))
        l.onProgress(toolEvent)

        then: 'one INFO ends the streak the WARN opened'
        def recovery = logs.list.find { it.level == Level.INFO }
        recovery.formattedMessage.contains('mid-round harvest recovered after 1 failure(s)')
        recovery.formattedMessage.contains('PT2M')

        cleanup:
        logs.detach()
    }
}
