package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches
import com.github.oinsio.gnomish.adapter.git.WorktreeSalvage
import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR15, D2 of add-tracker-port: the revocation salvage protocol run once
 * RevocationDetectedException has propagated out of engine.run — salvage the interrupted round's
 * leftovers, best-effort push, post a "work stopped" note, release the claim, and never touch the
 * tracker's logical state via park/recordAbort/finish. WorktreeSalvage and the branch-push port are final
 * adapter classes (no mocking) so this spec drives them for real against a bare-git-repo fixture,
 * mirroring WorktreeSalvageSpec/BranchPushSpec, and mocks only the Tracker port.
 */
class RevocationHandlerSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final TaskState STATE = TaskState.atStageStart('implement')
    private static final String BRANCH = 'gnomish/PROJ-1'

    def runner = new GitProcessRunner()
    Path repo
    Path bareRepo
    Tracker tracker = Mock()
    WorktreeSalvage worktreeSalvage
    TaskBranchGit branchPush
    RevocationHandler handler

    def setup() {
        repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'first'
        runner.run(repo, 'add', 'a.txt')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        runner.run(repo, 'checkout', '-q', '-b', BRANCH)

        bareRepo = initBareRepo(tempDir, 'origin.git')
        runner.run(repo, 'remote', 'add', 'origin', bareRepo.toString())
        runner.run(repo, 'push', 'origin', "${BRANCH}:${BRANCH}")

        worktreeSalvage = new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE)
        branchPush = new GitTaskBranches(runner)
        handler = new RevocationHandler(tracker, worktreeSalvage, branchPush)
    }

    private String currentHead() {
        runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
    }

    def "handle salvages leftovers, pushes, posts a note, releases, and returns Revoked"() {
        given: 'an interrupted round left an uncommitted leftover in the worktree'
        Files.writeString(repo.resolve('leftover.txt'), 'half-done work')
        def tipBefore = currentHead()

        when:
        def result = handler.handle(REF, 'PROJ-1', STATE, repo, BRANCH, 'task closed')

        then: 'the leftover was salvage-committed'
        currentHead() != tipBefore
        runner.run(repo, 'log', '-1', '--format=%s').stdout().trim() == 'gnomish: salvage'
        !worktreeSalvage.hasLeftovers()

        and: 'the branch was pushed to origin, up to the salvage commit'
        runner.run(bareRepo, 'rev-parse', BRANCH).stdout().trim() == currentHead()

        and: 'a stop note is posted and the claim is released'
        1 * tracker.postNote(REF, { String note ->
            note.contains('task closed')
        })
        1 * tracker.release(REF)

        and: 'state-changing tracker methods are never called'
        0 * tracker.park(*_)
        0 * tracker.recordAbort(*_)
        0 * tracker.finish(*_)

        and: 'the result carries the final state and the posted note'
        result instanceof TakeResult.Revoked
        result.finalState() == STATE
        result.note().contains('task closed')
    }

    def "handle is clean on an already-clean worktree: no salvage commit, still pushes and reports"() {
        given:
        def tipBefore = currentHead()

        when:
        def result = handler.handle(REF, 'PROJ-1', STATE, repo, BRANCH, 'claim held by another instance')

        then: 'no salvage commit landed'
        currentHead() == tipBefore

        and:
        1 * tracker.postNote(REF, _)
        1 * tracker.release(REF)
        result instanceof TakeResult.Revoked
    }

    def "a salvage failure propagates and the rest of the protocol never runs"() {
        given: 'a leftover, plus the git index lock already held by another process — salvage fails'
        Files.writeString(repo.resolve('leftover.txt'), 'stale')
        new File(repo.toFile(), '.git/index.lock').text = 'held by another process'

        when:
        handler.handle(REF, 'PROJ-1', STATE, repo, BRANCH, 'task closed')

        then:
        thrown(GitSalvageFailedException)
        0 * tracker.postNote(*_)
        0 * tracker.release(*_)
    }
}
