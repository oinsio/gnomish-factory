package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10 of add-git-workflow: uncommitted leftovers of an interrupted round, salvaged by default
 * (a service commit, not a round) or discarded on request, resetting to the last recorded round.
 */
class WorktreeSalvageSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    private void commit(Path repo, String fileName, String content) {
        new File(repo.toFile(), fileName).text = content
        runner.run(repo, 'add', fileName)
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', fileName)
    }

    def "hasLeftovers() is false on a clean worktree"() {
        given:
        def repo = initWorkingRepo(tempDir, 'clean')
        commit(repo, 'a.txt', 'first')
        def salvage = new WorktreeSalvage(runner, repo)

        expect:
        !salvage.hasLeftovers()
    }

    def "hasLeftovers() is true with an untracked file"() {
        given:
        def repo = initWorkingRepo(tempDir, 'dirty')
        commit(repo, 'a.txt', 'first')
        Files.writeString(repo.resolve('leftover.txt'), 'stale work')
        def salvage = new WorktreeSalvage(runner, repo)

        expect:
        salvage.hasLeftovers()
    }

    def "salvage() commits leftovers as-is with the fixed salvage message"() {
        given:
        def repo = initWorkingRepo(tempDir, 'salvage-me')
        commit(repo, 'a.txt', 'first')
        def tipBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
        Files.writeString(repo.resolve('leftover.txt'), 'half-done work')
        def salvage = new WorktreeSalvage(runner, repo)

        when:
        salvage.salvage('PROJ-1')

        then: 'a new commit landed with the fixed salvage message'
        def tipAfter = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
        tipAfter != tipBefore
        runner.run(repo, 'log', '-1', '--format=%s').stdout().trim() == 'gnomish: salvage'

        and: 'the leftover is committed, not discarded'
        Files.exists(repo.resolve('leftover.txt'))
        !salvage.hasLeftovers()
    }

    def "salvage() is a no-op on a clean worktree"() {
        given:
        def repo = initWorkingRepo(tempDir, 'clean-salvage')
        commit(repo, 'a.txt', 'first')
        def tipBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
        def salvage = new WorktreeSalvage(runner, repo)

        when:
        salvage.salvage('PROJ-2')

        then:
        runner.run(repo, 'rev-parse', 'HEAD').stdout().trim() == tipBefore
    }

    def "discard() resets tracked and untracked leftovers to HEAD, leaving HEAD unchanged"() {
        given:
        def repo = initWorkingRepo(tempDir, 'discard-me')
        commit(repo, 'a.txt', 'first')
        def tipBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()

        and: 'a modified tracked file plus a new untracked file — both uncommitted leftovers'
        Files.writeString(repo.resolve('a.txt'), 'modified content')
        Files.writeString(repo.resolve('untracked.txt'), 'new file')
        def salvage = new WorktreeSalvage(runner, repo)

        when:
        salvage.discard()

        then: 'HEAD is unchanged — the last recorded round is still the tip'
        runner.run(repo, 'rev-parse', 'HEAD').stdout().trim() == tipBefore

        and: 'the tracked file is back to its committed content'
        Files.readString(repo.resolve('a.txt')) == 'first'

        and: 'the untracked leftover is gone'
        !Files.exists(repo.resolve('untracked.txt'))
        !salvage.hasLeftovers()
    }

    def "discard() is a no-op on a clean worktree"() {
        given:
        def repo = initWorkingRepo(tempDir, 'clean-discard')
        commit(repo, 'a.txt', 'first')
        def tipBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
        def salvage = new WorktreeSalvage(runner, repo)

        when:
        salvage.discard()

        then:
        runner.run(repo, 'rev-parse', 'HEAD').stdout().trim() == tipBefore
    }

    def "salvage() throws GitSalvageFailedException naming the taskId when the index lock is held"() {
        given: 'a leftover, plus the git index lock already held by another process — add/commit fail'
        def repo = initWorkingRepo(tempDir, 'locked')
        commit(repo, 'a.txt', 'first')
        Files.writeString(repo.resolve('leftover.txt'), 'stale')
        new File(repo.toFile(), '.git/index.lock').text = 'held by another process'
        def salvage = new WorktreeSalvage(runner, repo)

        when:
        salvage.salvage('PROJ-3')

        then:
        def ex = thrown(GitSalvageFailedException)
        ex.message.contains('PROJ-3')
    }
}
