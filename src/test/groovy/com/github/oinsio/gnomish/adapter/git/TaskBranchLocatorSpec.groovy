package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, FR13 of add-git-workflow: local -> remote-tracking -> narrow fetch of exactly
 * {@code gnomish/<task>} -> "not found", shared verbatim by resume (4.6) and inspection (5.2).
 */
class TaskBranchLocatorSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def locator = new TaskBranchLocator(runner)

    private void commit(Path repo, String fileName, String content) {
        new File(repo.toFile(), fileName).text = content
        runner.run(repo, 'add', fileName)
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', fileName)
    }

    private Path initBareWithBranch(Path parent, String repoName, String branch, String fileName, String content) {
        def bare = initBareRepo(parent, repoName)
        def seed = initWorkingRepo(parent, repoName + '-seed')
        commit(seed, 'seed.txt', 'seed')
        runner.run(seed, 'remote', 'add', 'origin', bare.toString())
        runner.run(seed, 'push', 'origin', 'HEAD:refs/heads/main')
        runner.run(seed, 'checkout', '-b', branch)
        commit(seed, fileName, content)
        runner.run(seed, 'push', 'origin', branch)
        bare
    }

    /**
     * Clones only {@code main} (single-branch) so task branches pushed to the bare remote are
     * genuinely absent from the clone's remote-tracking refs until the locator fetches them —
     * a plain {@code git clone} would fetch every branch upfront and defeat these specs' setup.
     */
    private Path cloneOf(Path bare, String name) {
        def dest = tempDir.resolve(name)
        def result = runner.run(
                tempDir, 'clone', '--branch', 'main', '--single-branch', bare.toString(), dest.toString())
        assert result.exitCode() == 0: "clone failed: ${result.stderr()}"
        dest
    }

    def "FR8: branch present locally is found without any fetch, even when origin lacks it entirely"() {
        given: 'a clone with the task branch created locally, and origin pointed at an unrelated bare repo without it'
        def clone = initWorkingRepo(tempDir, 'clone-local')
        commit(clone, 'a.txt', 'first')
        def creator = new TaskBranchCreator(runner)
        creator.createBranch(clone, 'PROJ-1', null)
        def emptyOrigin = initBareRepo(tempDir, 'empty-origin.git')
        runner.run(clone, 'remote', 'add', 'origin', emptyOrigin.toString())

        when:
        def location = locator.locate(clone, 'PROJ-1')

        then: 'found locally; the locator never needed to consult origin'
        location instanceof BranchLocation.Local
        (location as BranchLocation.Local).ref() == 'refs/heads/gnomish/PROJ-1'
    }

    def "FR8: branch already fetched as a remote-tracking ref is reused, no new fetch performed"() {
        given: 'origin has the branch, and the clone already fetched it once manually'
        def bare = initBareWithBranch(tempDir, 'origin2.git', 'gnomish/PROJ-2', 'f.txt', 'hi')
        def clone = cloneOf(bare, 'clone2')
        runner.run(clone, 'fetch', 'origin', 'gnomish/PROJ-2:refs/remotes/origin/gnomish/PROJ-2')
        assert runner.run(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/PROJ-2').exitCode() == 0

        and: 'origin is then broken, so a second fetch would fail'
        runner.run(clone, 'remote', 'set-url', 'origin', tempDir.resolve('does-not-exist.git').toString())

        when:
        def location = locator.locate(clone, 'PROJ-2')

        then: 'the locator succeeded using the already-present tracking ref, proving it did not need to fetch again'
        location instanceof BranchLocation.RemoteTracking
        (location as BranchLocation.RemoteTracking).ref() == 'refs/remotes/origin/gnomish/PROJ-2'
    }

    def "FR8: branch only on origin is narrow-fetched and becomes readable via the returned ref"() {
        given:
        def bare = initBareWithBranch(tempDir, 'origin3.git', 'gnomish/PROJ-3', 'f.txt', 'branch-content')
        def clone = cloneOf(bare, 'clone3')
        assert runner.run(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/PROJ-3').exitCode() != 0

        when:
        def location = locator.locate(clone, 'PROJ-3')

        then:
        location instanceof BranchLocation.RemoteTracking
        def ref = (location as BranchLocation.RemoteTracking).ref()
        ref == 'refs/remotes/origin/gnomish/PROJ-3'

        and: 'the resolved ref is readable end-to-end via git show'
        def show = runner.run(clone, 'show', "${ref}:f.txt")
        show.exitCode() == 0
        show.stdout().trim() == 'branch-content'
    }

    def "FR8: the narrow fetch retrieves exactly the target branch, never a second unrelated branch on origin"() {
        given: 'origin has the target branch plus an unrelated second gnomish branch'
        def bare = initBareWithBranch(tempDir, 'origin4.git', 'gnomish/PROJ-4', 'f.txt', 'wanted')
        def seed = initWorkingRepo(tempDir, 'origin4-seed2')
        runner.run(seed, 'remote', 'add', 'origin', bare.toString())
        runner.run(seed, 'fetch', 'origin', 'main')
        runner.run(seed, 'checkout', '-b', 'gnomish/OTHER', 'origin/main')
        commit(seed, 'other.txt', 'unrelated')
        runner.run(seed, 'push', 'origin', 'gnomish/OTHER')
        def clone = cloneOf(bare, 'clone4')

        when:
        locator.locate(clone, 'PROJ-4')

        then: 'only the target branch got a remote-tracking ref'
        runner.run(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/PROJ-4').exitCode() == 0

        and: 'the unrelated branch was never fetched'
        runner.run(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/OTHER').exitCode() != 0
    }

    def "FR13: branch absent everywhere is reported as not-found, not a crash"() {
        given: 'a clone pointed at a bare origin that has no branches at all'
        def bare = initBareRepo(tempDir, 'origin5.git')
        def clone = initWorkingRepo(tempDir, 'clone5')
        commit(clone, 'a.txt', 'first')
        runner.run(clone, 'remote', 'add', 'origin', bare.toString())

        when:
        def location = locator.locate(clone, 'PROJ-5')

        then:
        noExceptionThrown()
        location instanceof BranchLocation.NotFound
    }

    def "FR13: no origin configured at all is also reported as not-found, not a crash"() {
        given:
        def clone = initWorkingRepo(tempDir, 'clone-no-origin')
        commit(clone, 'a.txt', 'first')

        when:
        def location = locator.locate(clone, 'PROJ-6')

        then:
        noExceptionThrown()
        location instanceof BranchLocation.NotFound
    }

    def "FR8: branch name is sanitized via TaskIdSanitizer before locating"() {
        given:
        def clone = initWorkingRepo(tempDir, 'clone-sanitized')
        commit(clone, 'a.txt', 'first')
        new TaskBranchCreator(runner).createBranch(clone, 'PROJ 7: fix/it', null)

        when:
        def location = locator.locate(clone, 'PROJ 7: fix/it')

        then:
        location instanceof BranchLocation.Local
        (location as BranchLocation.Local).ref() == 'refs/heads/gnomish/PROJ-7-fix-it'
    }
}
