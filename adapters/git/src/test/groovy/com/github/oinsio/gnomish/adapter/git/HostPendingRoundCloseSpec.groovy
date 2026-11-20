package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR20 of migrate-verify-to-executor-contract (design D21, "The lag the write leaves behind"): a host
 * round with pending commits closes on the real medium — the round commit sits on the round's last
 * pending commit, and the {@code state.json} the pending commits left behind in the worktree is
 * recognised by identity as the factory's, never as the gnome's envelope edit.
 */
class HostPendingRoundCloseSpec extends Specification implements HostSnapshotRound {

    @TempDir
    Path tempDir

    def setup() {
        setupHostRound()
    }

    // Scenario: git/git-task-persistence / The factory's own pending commit is no envelope edit at the round close
    def "FR20: the round commit's parent is the round's last pending commit, and the pending commits' lag is no envelope edit"() {
        given: 'a snapshotted round with two pending commits; the worktree keeps the state.json it had'
        runRound(completed()) { gnomeWorks() }
        def snapshot = tip()
        persistPending('run-1')
        persistPending('run-1', 'run-2')
        def lastPending = tip()
        gitOutput(worktree(), 'status', '--porcelain', '--', '.gnomish-task') != ''

        when:
        persistRound()

        then: 'no boundary violation: the round commit lands on the last pending commit, registered'
        def round = tip()
        registeredId(cloneDir, TASK) == round
        gitOutput(cloneDir, 'rev-parse', round + '^') == lastPending
        gitOutput(cloneDir, 'rev-list', '--first-parent', "${snapshot}..${round}").readLines().size() == 3

        and: 'the round commit carries the factory\'s own state.json, with no pending entry left'
        def state = fileAt(round, '.gnomish-task/state.json')
        !state.contains('run-1')
        changedIn(round).contains('.gnomish-task/state.json')
    }

    // Scenario: git/git-task-persistence / The factory's own pending commit is no envelope edit at the round close
    def "FR20: a state.json the worktree holds that no registered commit of the round carries is still the gnome's edit"() {
        given:
        runRound(completed()) { gnomeWorks() }
        persistPending('run-1')
        Files.writeString(worktree().resolve('.gnomish-task/state.json'), '{"forged":true}')

        when:
        persistRound()

        then:
        def thrown = thrown(RoundBoundaryViolationException)
        thrown.message.contains('.gnomish-task/state.json')
    }

    // FR20 (design D21): a recognised lag that cannot be restored fails the round close, naming it
    def "FR20: a pending commit's lag the round close cannot restore fails the persist"() {
        given:
        runRound(completed()) { gnomeWorks() }
        persistPending('run-1')
        def gitDir = Path.of(gitOutput(worktree(), 'rev-parse', '--path-format=absolute', '--git-dir'))
        Files.writeString(gitDir.resolve('index.lock'), '')

        when:
        persistRound()

        then:
        def failed = thrown(GitPersistFailedException)
        failed.message.contains('pending-commit lag')

        cleanup:
        Files.deleteIfExists(gitDir.resolve('index.lock'))
    }
}
