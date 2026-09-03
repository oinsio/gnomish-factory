package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import spock.lang.Specification
import spock.lang.TempDir
/**
 * FR10 of add-git-workflow: uncommitted leftovers of an interrupted round, salvaged by default
 * (a service commit, not a round) or discarded on request, resetting to the last recorded round.
 *
 * <p>FR5 of harden-task-branch-contract: factory-owned {@code .gnomish-task/} paths are restored
 * from the tip rather than salvaged, per the shared {@link FactoryOwnedPaths} policy.
 */
class WorktreeSalvageSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    def "hasLeftovers() is false on a clean worktree"() {
        given:
        def repo = initWorkingRepo(tempDir, 'clean')
        commit(repo, 'a.txt', 'first')
        def salvage = new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE)

        expect:
        !salvage.hasLeftovers()
    }

    def "hasLeftovers() is true with an untracked file"() {
        given:
        def repo = initWorkingRepo(tempDir, 'dirty')
        commit(repo, 'a.txt', 'first')
        Files.writeString(repo.resolve('leftover.txt'), 'stale work')
        def salvage = new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE)

        expect:
        salvage.hasLeftovers()
    }

    def "salvage() commits leftovers as-is with the fixed salvage message"() {
        given:
        def repo = initWorkingRepo(tempDir, 'salvage-me')
        commit(repo, 'a.txt', 'first')
        def tipBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
        Files.writeString(repo.resolve('leftover.txt'), 'half-done work')
        def salvage = new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE)

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

    // FR5, design D11 of harden-task-branch-contract: a process killed mid-round can leave a
    // stale or half-applied state.json behind. Salvage commits the gnome's work files and restores
    // the factory's own files from the tip — the branch, not the dirty worktree, says what the
    // recorded rounds are.
    def "salvage() restores factory-owned files from the tip instead of committing them"() {
        given: 'a task branch carrying committed factory files, plus a dying round\'s dirt'
        def repo = initWorkingRepo(tempDir, 'factory-owned')
        Files.createDirectories(repo.resolve('.gnomish-task/decisions'))
        Files.writeString(repo.resolve('.gnomish-task/state.json'), '{"recorded":true}')
        runner.run(repo, 'add', '-A')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'started')

        and: 'the worktree holds an overwritten state.json, a stray factory file, gnome work and a decision file'
        Files.writeString(repo.resolve('.gnomish-task/state.json'), '{ truncated')
        Files.writeString(repo.resolve('.gnomish-task/task.json'), 'stray')
        Files.writeString(repo.resolve('.gnomish-task/decisions/build-a0.json'), '{"asked":true}')
        Files.writeString(repo.resolve('work.txt'), 'half-done work')

        when:
        new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE).salvage('PROJ-1')

        then: 'the gnome\'s work file is committed'
        runner.run(repo, 'show', 'HEAD:work.txt').stdout().trim() == 'half-done work'

        and: 'the factory\'s state.json is the tip\'s, not the dirty one, and the stray never landed'
        runner.run(repo, 'show', 'HEAD:.gnomish-task/state.json').stdout().trim() == '{"recorded":true}'
        runner.run(repo, 'cat-file', '-e', 'HEAD:.gnomish-task/task.json').exitCode() != 0
        !Files.exists(repo.resolve('.gnomish-task/task.json'))

        and: 'the one gnome-writable path under .gnomish-task/ is salvaged like any work file'
        runner.run(repo, 'show', 'HEAD:.gnomish-task/decisions/build-a0.json').stdout().trim() == '{"asked":true}'
    }

    // FR5: when the ONLY dirt was a factory file, the restore leaves nothing to salvage — and an
    // empty salvage commit is never made.
    def "salvage() makes no commit when the only leftover was a factory-owned file"() {
        given:
        def repo = initWorkingRepo(tempDir, 'factory-only-dirt')
        Files.createDirectories(repo.resolve('.gnomish-task'))
        Files.writeString(repo.resolve('.gnomish-task/state.json'), '{"recorded":true}')
        runner.run(repo, 'add', '-A')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'started')
        def tipBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
        Files.writeString(repo.resolve('.gnomish-task/state.json'), '{ truncated')

        when:
        new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE).salvage('PROJ-1')

        then: 'no salvage commit, and the tip\'s state.json is back in the worktree'
        runner.run(repo, 'rev-parse', 'HEAD').stdout().trim() == tipBefore
        Files.readString(repo.resolve('.gnomish-task/state.json')) == '{"recorded":true}'
    }

    def "salvage() is a no-op on a clean worktree"() {
        given:
        def repo = initWorkingRepo(tempDir, 'clean-salvage')
        commit(repo, 'a.txt', 'first')
        def tipBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
        def salvage = new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE)

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
        def salvage = new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE)

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

    // FR5 of harden-logging-observability, mirrored on EnvironmentSalvage's own degrade path: a
    // discard step that did not run leaves the very leftovers it exists to remove, so the next
    // round starts on a working copy nobody expects. Best effort, but never silent.
    def "FR5: a discard step git refuses warns that the leftovers stay"() {
        given:
        def repo = initWorkingRepo(tempDir, 'refused-discard')
        commit(repo, 'a.txt', 'first')
        Files.writeString(repo.resolve('a.txt'), 'modified content')

        and: 'a stand-in git that refuses reset and clean, delegating status unchanged'
        def fakeGit = tempDir.resolve('refuse-discard-git.sh')
        fakeGit.toFile().text = """#!/bin/sh
if [ "\$1" = "reset" ] || [ "\$1" = "clean" ]; then
  echo 'fatal: stand-in git refuses' >&2
  exit 128
fi
exec git "\$@"
"""
        fakeGit.toFile().setExecutable(true)
        def salvage = new WorktreeSalvage(new GitProcessRunner(fakeGit.toString()), repo, ClaimEpochSource.NONE)
        def logs = LogCaptureSupport.attach(WorktreeSalvage)

        when:
        salvage.discard()
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'the leftovers really did stay'
        Files.readString(repo.resolve('a.txt')) == 'modified content'

        and: 'both refused steps are named'
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 2
        warnings[0].formattedMessage.contains('reset --hard HEAD')
        warnings[1].formattedMessage.contains('clean -fd')
        warnings.every {
            it.formattedMessage.startsWith(OperatorEvent.WORKTREE_DISCARD_STEP_FAILED.head())
        }
        warnings.every {
            it.formattedMessage.contains('leftovers stay in the worktree')
        }
    }

    def "discard() is a no-op on a clean worktree"() {
        given:
        def repo = initWorkingRepo(tempDir, 'clean-discard')
        commit(repo, 'a.txt', 'first')
        def tipBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
        def salvage = new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE)

        when:
        salvage.discard()

        then:
        runner.run(repo, 'rev-parse', 'HEAD').stdout().trim() == tipBefore
    }

    // FR5: the restore is what makes the branch — not the dirty worktree — the source of truth for
    // factory-owned files. A restore that FAILED must fail the salvage: swallowing its exit code
    // lets the dying round's half-written state.json ride into the salvage commit, which is exactly
    // the outcome the restore exists to prevent.
    def "salvage() fails rather than committing a factory-owned file the restore could not put back"() {
        given: 'a tip carrying the recorded state.json, plus a dying round\'s truncated one'
        def repo = initWorkingRepo(tempDir, 'restore-fails')
        Files.createDirectories(repo.resolve('.gnomish-task/decisions'))
        Files.writeString(repo.resolve('.gnomish-task/state.json'), '{"recorded":true}')
        Files.writeString(repo.resolve('.gnomish-task/decisions/.keep'), '')
        runner.run(repo, 'add', '-A')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'started')
        Files.writeString(repo.resolve('.gnomish-task/state.json'), '{ truncated')
        Files.writeString(repo.resolve('work.txt'), 'half-done work')

        and: 'the state directory is unwritable, so the checkout cannot unlink the truncated file'
        def stateDir = repo.resolve('.gnomish-task')
        def original = Files.getPosixFilePermissions(stateDir)
        Files.setPosixFilePermissions(
                stateDir, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))

        when:
        new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE).salvage('PROJ-4')

        then: 'the failed restore is reported, not swallowed'
        def ex = thrown(GitSalvageFailedException)
        ex.message.contains('PROJ-4')

        and: 'and the truncated state.json never reached a commit'
        runner.run(repo, 'show', 'HEAD:.gnomish-task/state.json').stdout().trim() == '{"recorded":true}'

        cleanup:
        Files.setPosixFilePermissions(stateDir, original)
    }

    def "salvage() throws GitSalvageFailedException naming the taskId when the index lock is held"() {
        given: 'a leftover, plus the git index lock already held by another process — add/commit fail'
        def repo = initWorkingRepo(tempDir, 'locked')
        commit(repo, 'a.txt', 'first')
        Files.writeString(repo.resolve('leftover.txt'), 'stale')
        new File(repo.toFile(), '.git/index.lock').text = 'held by another process'
        def salvage = new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE)

        when:
        salvage.salvage('PROJ-3')

        then:
        def ex = thrown(GitSalvageFailedException)
        ex.message.contains('PROJ-3')
    }
}
