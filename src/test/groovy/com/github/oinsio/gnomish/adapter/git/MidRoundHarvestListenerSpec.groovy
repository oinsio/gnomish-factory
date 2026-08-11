package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.agent.AgentProgressEvent
import com.github.oinsio.gnomish.adapter.environment.TaskExecutionEnvironment
import com.github.oinsio.gnomish.domain.engine.port.Clock
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
        new MidRoundHarvestListener(env, runner, clone, 'PROJ-9', BRANCH, clock, interval)
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
}
