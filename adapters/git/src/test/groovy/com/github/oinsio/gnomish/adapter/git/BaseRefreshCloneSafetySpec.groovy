package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6 of add-base-ref-resolution (ADR 0006, "What the fetch may and may not change"): the factory
 * clone is also the operator's clone, and the promise D7 of add-git-workflow made about it survives
 * this change intact for everything except the one ref the refresh names.
 *
 * <p>The clone here is deliberately hostile: dirty working tree, staged index, HEAD on an unrelated
 * branch, a local branch and a pre-existing tag of its own, and a {@code remote.origin.fetch} wide
 * enough to clobber local branches if the refresh ever let git apply it.
 */
class BaseRefreshCloneSafetySpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()

    private Path origin
    private Path work
    private Path clone

    def setup() {
        work = initWorkingRepo(tempDir, 'work')
        gitOutput(work, 'checkout', '-b', 'main')
        commit(work, 'a.txt', 'one')
        gitOutput(work, 'checkout', '-b', 'develop')
        commit(work, 'b.txt', 'two')
        origin = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', origin.toString())
        assert gitExitCode(work, 'push', 'origin', 'main', 'develop') == 0
        assert gitExitCode(tempDir, 'clone', origin.toString(), 'clone') == 0
        clone = tempDir.resolve('clone')

        // The operator's own state: a local branch, a local tag, a dirty tree and a staged index.
        assert gitExitCode(clone, 'checkout', '-b', 'my-work') == 0
        assert gitExitCode(clone, 'tag', 'my-tag', 'refs/remotes/origin/main') == 0
        Files.writeString(clone.resolve('a.txt'), 'edited by the operator')
        Files.writeString(clone.resolve('staged.txt'), 'staged by the operator')
        assert gitExitCode(clone, 'add', 'staged.txt') == 0
    }

    private BaseRefresh refresh() {
        new BaseRefresh(runner, new GitInfrastructureRetry(
                        { Duration ignored -> } as Sleeper, GitInfrastructureRetry.DEFAULT_ATTEMPTS, Duration.ofMillis(1)))
    }

    /** Everything the refresh promised not to move, as one comparable snapshot. */
    private Map<String, String> untouchable() {
        [
            head: gitOutput(clone, 'symbolic-ref', 'HEAD'),
            heads: gitOutput(clone, 'for-each-ref', '--format=%(refname) %(objectname)', 'refs/heads'),
            tags: gitOutput(clone, 'for-each-ref', '--format=%(refname) %(objectname)', 'refs/tags'),
            otherTracking: gitOutput(clone, 'for-each-ref', '--format=%(refname) %(objectname)',
            'refs/remotes/origin/main'),
            worktree: Files.readString(clone.resolve('a.txt')),
            index: gitOutput(clone, 'diff', '--cached', '--name-only'),
        ]
    }

    def "FR6: refreshing a branch moves its tracking ref and nothing else in the operator's clone"() {
        given:
        def before = untouchable()
        gitOutput(work, 'checkout', 'develop')
        commit(work, 'c.txt', 'three')
        assert gitExitCode(work, 'push', 'origin', 'develop') == 0

        when:
        def outcome = refresh().refresh(clone, 'develop')

        then:
        outcome instanceof BaseRefreshOutcome.Refreshed
        gitOutput(clone, 'rev-parse', 'refs/remotes/origin/develop') ==
                (outcome as BaseRefreshOutcome.Refreshed).commit()

        and: 'HEAD, local branches, local tags, the other tracking ref, the tree and the index all stand'
        untouchable() == before
    }

    def "FR6: a non-standard remote.origin.fetch cannot make the refresh move anything extra"() {
        given: 'a configured refspec that would write straight into refs/heads if git applied it'
        assert gitExitCode(clone, 'config', 'remote.origin.fetch', '+refs/heads/*:refs/heads/*') == 0
        def before = untouchable()

        when:
        def outcome = refresh().refresh(clone, 'develop')

        then: 'the empty --refmap= is what keeps the configured mapping out of it'
        outcome instanceof BaseRefreshOutcome.Refreshed
        untouchable() == before
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', 'refs/heads/develop') != 0
    }

    def "FR6: a tag refresh writes only its own tag and auto-follows no others"() {
        given: 'origin carries two tags, only one of which is the base'
        gitOutput(work, 'checkout', 'develop')
        gitOutput(work, 'tag', 'v2.0')
        gitOutput(work, 'tag', 'v2.1')
        assert gitExitCode(work, 'push', 'origin', 'v2.0', 'v2.1') == 0
        def operatorTag = gitOutput(clone, 'rev-parse', 'refs/tags/my-tag')
        def before = untouchable()

        when:
        def outcome = refresh().refresh(clone, 'v2.0')

        then:
        outcome instanceof BaseRefreshOutcome.Refreshed
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', 'refs/tags/v2.0') == 0

        and: 'the unrelated tag stayed on origin, and the operator\'s own tag is untouched'
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', 'refs/tags/v2.1') != 0
        gitOutput(clone, 'rev-parse', 'refs/tags/my-tag') == operatorTag

        and: 'everything else the promise covers is unchanged'
        untouchable().subMap('head', 'heads', 'otherTracking', 'worktree', 'index') ==
                before.subMap('head', 'heads', 'otherTracking', 'worktree', 'index')
    }

    def "FR6: pull is never how a base is refreshed"() {
        given:
        Path log = tempDir.resolve('argv.log')
        def recording = new GitProcessRunner(recordingGit(log).toString())

        when:
        new BaseRefresh(recording, new GitInfrastructureRetry(
                        { Duration ignored -> } as Sleeper, 1, Duration.ofMillis(1))).refresh(clone, 'develop')

        then:
        !recordedSubcommands(log).contains('pull')
        !recordedSubcommands(log).contains('checkout')
        !recordedSubcommands(log).contains('merge')
    }
}
