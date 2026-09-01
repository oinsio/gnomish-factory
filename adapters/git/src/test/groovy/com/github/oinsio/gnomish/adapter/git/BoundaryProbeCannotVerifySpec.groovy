package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of harden-logging-observability: boundary verification has three outcomes, never two.
 * A diff that failed prints no paths for the same reason a clean one prints none, so reading
 * its empty stdout as "the state directory is untouched" turns an infrastructure fault into a
 * silent pass. Both boundary media are covered here (M6): the host worktree diff
 * ({@link RoundBoundaryCheck}) and the harvested-ref diff ({@link HarvestedBoundaryCheck}),
 * which are a declared sync pair.
 */
class BoundaryProbeCannotVerifySpec extends Specification implements BareGitRepoFixture, FailingSubcommandGitFixture {

    @TempDir
    Path tempDir

    static final String BRANCH = 'gnomish/PROJ-1'
    static final AttemptKey KEY = new AttemptKey('PROJ-1', 'implement', 1)

    Path repo

    def setup() {
        repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'first'
        commitAll(repo)
        assert gitExitCode(repo, 'checkout', '-q', '-b', BRANCH) == 0
    }

    private String head() {
        gitOutput(repo, 'rev-parse', 'HEAD')
    }

    private void commitStateDirectoryChange() {
        new File(repo.toFile(), '.gnomish-task').mkdirs()
        new File(repo.toFile(), '.gnomish-task/state.json').text = '{"tampered":true}'
        commitAll(repo, 'gnome touched the state directory')
    }

    def "FR13: a failed worktree boundary diff is cannot-verify, not a clean boundary"() {
        given: 'the boundary diff fails while every other git read still answers'
        def previousTip = head()
        def check = new RoundBoundaryCheck(new GitProcessRunner(gitFailingOn(tempDir, 'diff').toString()), repo, BRANCH)

        when:
        check.verify('PROJ-1', KEY, previousTip)

        then: 'the round aborts as infrastructure, carrying the git evidence'
        def failure = thrown(GitPersistFailedException)
        failure.message.contains('round boundary diff')
        failure.message.contains(GIT_FAILURE_STDERR)

        and: 'nothing is attributed to the gnome'
        !failure.message.contains('modified by the gnome')
        !failure.message.contains('round-boundary protocol violated')
    }

    def "FR13: a seeded state-directory tamper is still a violation, not cannot-verify"() {
        given:
        def previousTip = head()
        commitStateDirectoryChange()
        def check = new RoundBoundaryCheck(new GitProcessRunner(), repo, BRANCH)

        when:
        check.verify('PROJ-1', KEY, previousTip)

        then:
        def violation = thrown(RoundBoundaryViolationException)
        violation.message.contains('.gnomish-task/ was modified by the gnome')
    }

    def "FR13: a cannot-verify boundary burns no attempt — no round commit is written"() {
        given: 'persistence whose boundary probe cannot run, over a worktree with real history'
        def persistence = new GitAttemptPersistence(
                new GitProcessRunner(gitFailingOn(tempDir, 'diff').toString()), repo, 'PROJ-1', ClaimEpochSource.NONE)
        def headBefore = head()

        when:
        persistence.persist('PROJ-1', TaskState.atStageStart('implement'), new ToolTrace(KEY, []))

        then:
        thrown(GitPersistFailedException)

        and: 'no attempt record reached the branch'
        head() == headBefore
        gitOutput(repo, 'log', '--format=%H', "${headBefore}..HEAD").isEmpty()
    }

    def "FR13: a failed harvested boundary diff is cannot-verify, not a clean boundary"() {
        given:
        def previousTip = head()
        commitStateDirectoryChange()
        def snapshot = head()
        def check = new HarvestedBoundaryCheck(new GitProcessRunner(gitFailingOn(tempDir, 'diff').toString()), repo)

        when:
        check.verify('PROJ-1', previousTip, snapshot, KEY)

        then: 'the tamper below it is never even reached — the probe itself failed'
        def failure = thrown(GitPersistFailedException)
        failure.message.contains('harvested boundary diff')
        failure.message.contains(GIT_FAILURE_STDERR)
    }

    def "FR13: a seeded tamper on harvested refs is still a violation"() {
        given:
        def previousTip = head()
        commitStateDirectoryChange()
        def snapshot = head()
        def check = new HarvestedBoundaryCheck(new GitProcessRunner(), repo)

        when:
        check.verify('PROJ-1', previousTip, snapshot, KEY)

        then:
        def violation = thrown(RoundBoundaryViolationException)
        violation.message.contains('.gnomish-task/state.json')
    }
}
