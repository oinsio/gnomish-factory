package com.github.oinsio.gnomish.gitobjects

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of tighten-spec-contract (design D13, amended 2026-10-13): a lifecycle commit and the factory
 * register land in one ref transaction — both refs name the new commit, or neither moved — so no
 * kill and no refused precondition can leave a factory closing commit above the register.
 */
class GitObjectsCommitAlongsideSpec extends Specification implements GitObjectsFixture {

    private static final String BRANCH = 'refs/heads/gnomish/t1'

    private static final String REGISTER = 'refs/gnomish-factory/t1'

    @TempDir
    Path tempDir

    // Scenario: git/git-task-persistence / The register follows every closing commit
    def "FR13: a created branch and its register name the new commit together"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when:
        def created = git.commit(request(Optional.empty(), base, 'v1').alongside(REGISTER))

        then:
        git.resolveRef(BRANCH).get() == created
        git.resolveRef(REGISTER).get() == created
    }

    def "FR13: an advanced branch moves its register with it"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()
        def first = git.commit(request(Optional.empty(), base, 'v1').alongside(REGISTER))

        when:
        def second = git.commit(request(Optional.of(first), first, 'v2').alongside(REGISTER))

        then:
        git.resolveRef(BRANCH).get() == second
        git.resolveRef(REGISTER).get() == second
    }

    def "FR13: a refused precondition moves neither ref — #why"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()
        def first = git.commit(request(Optional.empty(), base, 'v1').alongside(REGISTER))

        when:
        git.commit(request(expected(first, base), first, 'v2').alongside(REGISTER))

        then:
        thrown(StaleTipException)
        git.resolveRef(BRANCH).get() == first
        git.resolveRef(REGISTER).get() == first

        where:
        why | expected
        'the tip moved' | { ObjectId tip, ObjectId other -> Optional.of(other) }
        'the branch exists already' | { ObjectId tip, ObjectId other ->
            Optional.<ObjectId> empty()
        }
    }

    def "a blank ref to move alongside is refused, and so is a missing one"() {
        given:
        def base = ObjectId.of('a' * 40)

        when:
        request(Optional.empty(), base, 'v1').alongside(' ')

        then:
        thrown(IllegalArgumentException)

        when:
        new CommitRequest(BRANCH, Optional.empty(), base, [], metadata(), null)

        then:
        thrown(IllegalArgumentException)
    }

    private CommitRequest request(Optional<ObjectId> expectedTip, ObjectId parent, String content) {
        new CommitRequest(BRANCH, expectedTip, parent, [
            new TreeEdit.PutFile('.gnomish-task/task.json', content.bytes)
        ], metadata())
    }
}
