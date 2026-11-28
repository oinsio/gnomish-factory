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
 * FR19 of migrate-verify-to-executor-contract (design D22): the host snapshot's medium on the real
 * medium — its boundary verdict before any commit, a commit step that fails landing nothing, the
 * decoration's round source over the run's cell, the empty round, and the step's two kill windows.
 */
class HostSnapshotMediumSpec extends Specification implements HostSnapshotRound {

    @TempDir
    Path tempDir

    def setup() {
        setupHostRound()
    }

    // FR19 (design D22): a snapshot that could not be committed fails the round's mechanics — the
    // engine's infrastructure failure — and lands nothing
    def "FR19: a snapshot whose #step fails throws a persist failure naming it, and moves nothing"() {
        given:
        def registered = registeredId(cloneDir, TASK)

        when:
        runRound(completed(), new OneStepAnswers(runner, matches, GitCommandResult.of(1, '', 'fatal: refused\n'))) {
            gnomeWorks()
        }

        then:
        def failure = thrown(GitPersistFailedException)
        failure.message.contains(step)
        registeredId(cloneDir, TASK) == registered
        cell.snapshot().isEmpty()

        where:
        step << [
            'restore of the factory-owned paths',
            'git add -A',
            'git commit-tree'
        ]
        matches << [
            { List a ->
                a[0] == 'restore'
            },
            { List a ->
                a[0] == 'add'
            },
            { List a ->
                a[0] == 'commit-tree'
            }
        ]
    }

    // FR13 of harden-logging-observability; FR19 (design D22): a HEAD read that established no tip is
    // cannot-verify — the snapshot is declined, never judged on a blank base
    def "FR19: a HEAD that resolves to no object id declines the snapshot"() {
        given:
        def registered = registeredId(cloneDir, TASK)
        def garbled = new OneStepAnswers(runner, { List a ->
            a == ['rev-parse', 'HEAD']
        }, GitCommandResult.of(0, 'not-a-commit\n', ''))

        when:
        runRound(completed(), garbled) { gnomeWorks() }

        then:
        registeredId(cloneDir, TASK) == registered
        cell.snapshot().isEmpty()
    }

    // FR19 (design D22): the medium's verdict is the boundary's own — the committed half first, a
    // read that established nothing cannot-verify — never a clean answer by default
    def "FR19: the worktree medium judges #shape"() {
        given:
        setup.call(this)
        def order = new SnapshotOrder(new AttemptKey(TASK, 'implement', ROUND), cell.opened(), 'unused', cell.registeredChain())

        expect:
        new WorktreeSnapshot(git.call(this), worktree(), TASK, ClaimEpochSource.NONE).judge(order).getClass().simpleName == verdict

        where:
        shape | setup | git || verdict
        'a forged service subject as violated' | { s ->
            Files.writeString(s.worktree().resolve('A.java'), 'x'); s.commitAll(s.worktree(), 'gnomish: round x#0')
        } | { s ->
            s.runner
        } || 'Violated'
        'an unreadable HEAD as cannot-verify' | { s -> } | { s ->
            new OneStepAnswers(s.runner, { List a ->
                a == ['rev-parse', 'HEAD']
            }, GitCommandResult.of(128, '', 'fatal: bad\n'))
        } || 'CannotVerify'
        'a HEAD printing no id as cannot-verify' | { s -> } | { s ->
            new OneStepAnswers(s.runner, { List a ->
                a == ['rev-parse', 'HEAD']
            }, GitCommandResult.of(0, 'garbled\n', ''))
        } || 'CannotVerify'
        'an envelope edit as violated' | { s ->
            Files.writeString(s.worktree().resolve('.gnomish-task/task.json'), '{}')
        } | { s ->
            s.runner
        } || 'Violated'
    }

    // FR1 of wire-host-mid-round-push; FR19 (design D22): the round source the decoration builds opens
    // its rounds in the cell it was handed, the one the persistence reads
    def "FR19: the host-git decoration's round source opens its rounds in the run's cell"() {
        given:
        def host = Stub(com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource)

        expect:
        new GitHostRounds(runner, new VirtualClock(), ClaimEpochSource.NONE).decorate(host, cell).identity().is(cell)
    }

    // FR19 (design D22): an empty round is still snapshotted, as the box's --allow-empty is
    def "FR19: a completed round that changed nothing still gets its snapshot commit"() {
        given:
        def registered = registeredId(cloneDir, TASK)

        when:
        runRound(completed()) {}

        then:
        def snapshot = tip()
        snapshot != registered
        registeredId(cloneDir, TASK) == snapshot
        gitOutput(cloneDir, 'rev-parse', snapshot + '^{tree}') == gitOutput(cloneDir, 'rev-parse', registered + '^{tree}')
    }

    // Kill window of design D22: a kill after the snapshot's ref transaction and before the round's
    // persist leaves the snapshot registered — the interrupted verification the one recogniser reads
    def "FR19: a kill after the host snapshot leaves it registered, read by the one recogniser as an interrupted verification"() {
        given:
        def token = cell.opened()

        when: 'the round snapshots, and the factory dies before the persist'
        runRound(completed()) { gnomeWorks() }
        def snapshot = tip()

        then:
        def pending = new SnapshotTipCheck(runner, cloneDir).inspect(TASK)
        pending.isPresent()
        pending.get().attemptCommit() == snapshot
        pending.get().stage() == 'implement'
        pending.get().round() == ROUND
        pending.get().token() == token
    }

    // Kill window of design D22: a kill before the snapshot's ref transaction leaves the register on
    // the previous closing commit — "during the agent", the ordinary resume's
    def "FR19: a snapshot whose ref transaction never landed leaves the round to the ordinary resume"() {
        given:
        def registered = registeredId(cloneDir, TASK)
        gnomeWorks()

        when: 'the medium is judged and nothing is committed: the state a kill before the transaction leaves'
        def verdict = new WorktreeSnapshot(runner, worktree(), TASK, ClaimEpochSource.NONE).judge(
                new SnapshotOrder(new AttemptKey(TASK, 'implement', ROUND), cell.opened(), 'unused', cell.registeredChain()))

        then:
        verdict instanceof RangeVerdict.Clean
        registeredId(cloneDir, TASK) == registered
        new SnapshotTipCheck(runner, cloneDir).inspect(TASK).isEmpty()
    }
}
