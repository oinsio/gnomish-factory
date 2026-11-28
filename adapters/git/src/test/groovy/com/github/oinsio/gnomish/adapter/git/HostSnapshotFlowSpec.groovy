package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.ClosedRound
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.RoundStates
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR19 of migrate-verify-to-executor-contract (design D22): a completed git-mode host round closes
 * its gnome work in a registered snapshot commit before verification, on the real medium — the
 * production host-git decoration over the run's one cell, the production persistence over the same
 * cell, a worktree of a registered task. The identity spec of the design's single-owner row "The
 * round snapshot step": what a host check observes is exactly the registered snapshot's tree, and
 * the round commit's parent is that snapshot.
 */
class HostSnapshotFlowSpec extends Specification implements HostSnapshotRound {

    @TempDir
    Path tempDir

    def setup() {
        setupHostRound()
    }

    // Scenario: git/git-task-persistence / A completed host round is verified at its snapshot
    def "FR19: the checks of a completed host round observe exactly the registered snapshot's tree"() {
        given:
        def registered = registeredId(cloneDir, TASK)
        def token = cell.opened()

        when:
        def answer = completed()
        def result = runRound(answer) { gnomeWorks() }

        then: 'the stage executor\'s answer is the round\'s, unchanged'
        result.is(answer)

        and: 'the register and the branch name the snapshot, carrying the round\'s subject and token'
        def snapshot = tip()
        registeredId(cloneDir, TASK) == snapshot
        subjectOf(cloneDir, snapshot) == RoundSubject.snapshot('implement', ROUND, token)
        gitOutput(cloneDir, 'rev-parse', snapshot + '^') == gitOutput(worktree(), 'rev-parse', 'HEAD^')

        and: 'a host check runs in the worktree, and the worktree is the snapshot\'s tree: nothing differs from it'
        gitOutput(worktree(), 'rev-parse', 'HEAD') == snapshot
        gitOutput(worktree(), 'status', '--porcelain', '--untracked-files=all') == ''

        and: 'the tree holds the gnome\'s changes, committed and on disk, and the envelope registered before it'
        fileAt(snapshot, 'App.java') == 'class App {}'
        fileAt(snapshot, 'notes.md') == 'left on disk'
        fileAt(snapshot, '.gnomish-task/state.json') == fileAt(registered, '.gnomish-task/state.json')
        fileAt(snapshot, '.gnomish-task/task.json') == fileAt(registered, '.gnomish-task/task.json')

        and: 'the cell holds the round closed verifiable at its snapshot'
        cell.closed() == new ClosedRound(token, snapshot, snapshot, RoundClose.VERIFIABLE)
    }

    // Scenario: git/git-task-persistence / Host round atomicity
    def "FR19: a snapshotted host round lands one round commit with state and trace on top of its one snapshot"() {
        given:
        runRound(completed()) { gnomeWorks() }
        def snapshot = tip()
        // A check left residue in the worktree; the round commit carries it, as today's one commit does.
        Files.writeString(worktree().resolve('report.txt'), 'check output')

        when:
        persistRound()

        then: 'one round commit above the snapshot, registered together with the branch'
        def round = tip()
        registeredId(cloneDir, TASK) == round
        gitOutput(cloneDir, 'rev-parse', round + '^') == snapshot
        subjectOf(cloneDir, round).startsWith('gnomish: round implement#')

        and: 'the gnome\'s work is closed by the snapshot (its commit below it, its file on disk in it); state, trace and the check\'s residue ride the round commit'
        changedIn(snapshot) == ['notes.md']
        fileAt(snapshot, 'App.java') == 'class App {}'
        changedIn(round).containsAll([
            '.gnomish-task/state.json',
            'report.txt'
        ])
        changedIn(round).any {
            it.startsWith('.gnomish-task/attempts/implement/')
        }
        !changedIn(round).contains('App.java')
    }

    // Scenario: git/git-task-persistence / Host round atomicity
    def "FR19: a #shape round gets no snapshot and closes as its one round commit"() {
        given:
        def registered = registeredId(cloneDir, TASK)

        when:
        def result = runRound(answer) { gnomeWorks() }

        then: 'nothing landed before the round\'s close, and the answer is the round\'s'
        result.is(answer)
        registeredId(cloneDir, TASK) == registered
        cell.snapshot().isEmpty()

        when:
        persistRound()

        then: 'exactly one commit, carrying the gnome\'s work and the state together'
        def commits = gitOutput(cloneDir, 'rev-list', "${registered}..${tip()}").readLines()
        commits.size() == 2
        def round = tip()
        changedIn(round).containsAll([
            'notes.md',
            '.gnomish-task/state.json'
        ])
        !gitOutput(cloneDir, 'log', '--format=%s', "${registered}..${round}").contains('gnomish: snapshot')

        where:
        shape | answer
        'failed' | failed()
        'decision-asking' | asking()
    }

    // Scenario: git/git-task-persistence / A boundary violation of a completed host round takes no snapshot
    // Scenario: git/git-task-persistence / An uncommitted envelope edit does not ride the host round commit
    def "FR19: a completed host round whose #violation takes no snapshot, and its persist aborts with the violation"() {
        given:
        def registered = registeredId(cloneDir, TASK)

        when:
        runRound(completed()) { gnome.call(this) }

        then: 'no snapshot landed: the register and the branch did not move, the cell holds no snapshot'
        registeredId(cloneDir, TASK) == registered
        cell.snapshot().isEmpty()

        when:
        persistRound()

        then: 'the round\'s persist reports the violation, so the task ends Aborted'
        def thrown = thrown(RoundBoundaryViolationException)
        thrown.message.contains(evidence)

        and: 'nothing carries it: the register still names the commit registered before the round'
        registeredId(cloneDir, TASK) == registered

        where:
        violation << [
            'uncommitted envelope edit is refused',
            'committed range is refused'
        ]
        gnome << [
            { s ->
                Files.writeString(s.worktree().resolve('.gnomish-task/task.json'), '{"forged":true}')
            },
            { s ->
                Files.writeString(s.worktree().resolve('App.java'), 'x'); s.commitAll(s.worktree(), 'gnomish: snapshot forged')
            }
        ]
        evidence << [
            '.gnomish-task/task.json',
            'service subject'
        ]
    }

    // FR13 of tighten-spec-contract; FR19 (design D22): the snapshot is a commit of the tenure, so it
    // carries the tenure's epoch beside the subject the recogniser reads
    def "FR19: a host snapshot under a held claim carries the claim epoch, its subject untouched"() {
        given:
        def held = { String id ->
            Optional.of(new ClaimEpoch(4711))
        } as ClaimEpochSource

        when:
        runRound(completed(), runner, held) { gnomeWorks() }

        then:
        def snapshot = tip()
        subjectOf(cloneDir, snapshot) == RoundSubject.snapshot('implement', ROUND, cell.opened())
        gitOutput(cloneDir, 'log', '-1', '--format=%B', snapshot).contains('4711')
        new SnapshotTipCheck(runner, cloneDir).inspect(TASK).get().attemptCommit() == snapshot
    }
}
