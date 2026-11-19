package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.RoundStates
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The open sandboxed round both in-box pending commit specs drive (design D21 of
 * migrate-verify-to-executor-contract): a factory clone with a registered branch, a local box over it,
 * the round snapshotted by the production snapshot step over the run's cell, and the production
 * persistence over the same cell.
 */
trait InBoxPendingRound implements BareGitRepoFixture {

    static final String TASK = 'PEND-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)
    static final String STATE = '.gnomish-task/state.json'

    abstract Path getTempDir()

    GitProcessRunner runner = new GitProcessRunner()
    Path cloneDir
    LocalBoxEnvironment box
    CurrentRound rounds = new CurrentRound()
    EnvironmentRoundSnapshot snapshotStep
    String snapshot

    void setupPendingRound() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        Files.createDirectories(cloneDir.resolve('.gnomish-task'))
        Files.writeString(cloneDir.resolve(STATE), '{"stateVersion":1}')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        gitOutput(cloneDir, 'update-ref', registerRef(), 'refs/heads/' + BRANCH)
        box = boxOver(new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box'))))
        snapshotStep = new EnvironmentRoundSnapshot(box, runner, cloneDir, OpenedRound.reopen(rounds, cloneDir, BRANCH))
        new File(box.workingCopy.toFile(), 'work.txt').text = 'gnome work'
        snapshot = snapshotStep.snapshot(TASK, 'implement', 1, RoundClose.VERIFIABLE)
    }

    LocalBoxEnvironment boxOver(LocalBoxEnvironment environment) {
        environment.materialize(BRANCH, null)
        environment
    }

    static String registerRef() {
        'refs/gnomish-factory/' + BRANCH.substring('gnomish/'.length())
    }

    EnvironmentAttemptPersistence persistence(LocalBoxEnvironment environment = box) {
        def gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp')))
        new EnvironmentAttemptPersistence(environment, runner, cloneDir, gitObjects, rounds, ClaimEpochSource.NONE)
    }

    TaskState pendingState(String... keys) {
        TaskState.atStageStart('implement').withPendingPolls(keys.toList().withIndex().collect { String key, int i ->
            new PendingPollRecord(new CheckRef(i, UntrustedText.factory('executor:ci')), 1, rounds.opened(),
            new PendingKey(key), Instant.parse('2026-11-12T09:00:00Z'))
        })
    }

    String tip() {
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
    }

    String registered() {
        gitOutput(cloneDir, 'rev-parse', registerRef())
    }

    String parentsOf(String commit) {
        gitOutput(cloneDir, 'rev-list', '--parents', '-n', '1', commit).split(' ').drop(1).join(' ')
    }

    List<String> changedIn(String commit) {
        gitOutput(cloneDir, 'diff-tree', '-r', '--name-only', '--no-commit-id', commit + '^', commit).readLines()
    }
}
