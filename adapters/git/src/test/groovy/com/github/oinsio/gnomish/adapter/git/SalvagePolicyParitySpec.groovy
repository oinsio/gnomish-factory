package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5 of harden-task-branch-contract (design D11): host salvage ({@link WorktreeSalvage}) and
 * container salvage ({@link EnvironmentSalvage}) consume one ownership list, so the same dirty path
 * gets the same answer in both media. Each row dirties one path beside a gnome work file, runs both
 * salvages on equal starting points — a host worktree and an in-box clone ({@link
 * LocalBoxEnvironment}) of the same tip — and reads back whether the salvage commit carried it.
 */
class SalvagePolicyParitySpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'PAR-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()

    /** A repository whose tip carries a factory state file and a gnome source file. */
    private Path tipRepo(String name) {
        def repo = initWorkingRepo(tempDir, name)
        Files.createDirectories(repo.resolve('.gnomish-task'))
        Files.writeString(repo.resolve('.gnomish-task/state.json'), '{"recorded":true}')
        Files.createDirectories(repo.resolve('src'))
        Files.writeString(repo.resolve('src/main.txt'), 'main')
        commitAll(repo, 'tip')
        repo
    }

    private static void dirty(Path workingCopy, String path) {
        def file = workingCopy.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, 'dirty')
        Files.writeString(workingCopy.resolve('gnome-work.txt'), 'work')
    }

    private boolean carried(Path workingCopy, String path, String content = 'dirty') {
        gitExitCode(workingCopy, 'cat-file', '-e', "HEAD:${path}") == 0 && gitOutput(workingCopy, 'show', "HEAD:${path}") == content
    }

    private boolean hostCarries(String path) {
        def repo = tipRepo('host-' + System.nanoTime())
        gitOutput(repo, 'checkout', '-q', '-b', BRANCH)
        gitOutput(repo, 'update-ref', 'refs/gnomish-factory/' + TASK, 'HEAD')
        dirty(repo, path)
        new WorktreeSalvage(runner, repo, ClaimEpochSource.NONE).salvage(TASK)
        assert carried(repo, 'gnome-work.txt', 'work'): 'the host salvage commit landed'
        carried(repo, path)
    }

    private boolean containerCarries(String path) {
        def clone = tipRepo('clone-' + System.nanoTime())
        gitOutput(clone, 'branch', BRANCH)
        // The branch starts at a registered closing commit, as STARTED leaves it (design D13).
        gitOutput(clone, 'update-ref', 'refs/gnomish-factory/' + BRANCH.substring('gnomish/'.length()), 'refs/heads/' + BRANCH)
        def box = new LocalBoxEnvironment(clone, Files.createDirectories(tempDir.resolve('box-' + System.nanoTime())))
        box.materialize(BRANCH, null)
        dirty(box.workingCopy, path)
        new EnvironmentSalvage(box, ClaimEpochSource.NONE, new GitProcessRunner(), clone).salvage(TASK)
        assert carried(box.workingCopy, 'gnome-work.txt', 'work'): 'the in-box salvage commit landed'
        carried(box.workingCopy, path)
    }

    // Scenario: sandbox/execution-environment / Both adapters classify a path identically
    def "FR5: #path is #owner in both media — salvaged: #salvaged"() {
        expect:
        hostCarries(path) == salvaged
        containerCarries(path) == salvaged

        where:
        path | owner || salvaged
        '.gnomish-task/state.json' | 'factory-owned' || false
        '.gnomish-task/trace/build-a0.json' | 'factory-owned' || false
        '.gnomish-task/decisions/q-a0.json' | 'gnome-owned' || true
        'src/main.txt' | 'gnome-owned' || true
        'notes/new.txt' | 'gnome-owned' || true
    }
}
