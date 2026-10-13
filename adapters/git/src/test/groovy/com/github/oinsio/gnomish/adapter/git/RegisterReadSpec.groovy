package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchStateResult
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.TipRelation
import com.github.oinsio.gnomish.domain.engine.Position
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * F135, FR13, NFR-S2 of tighten-spec-contract (design D13): every reader of task-branch state reads
 * the envelope at the registered commit, never at the tip. Over a real repository the gnome commits
 * above the register — a {@code Completed} outcome, a gate position, a rewritten base pin — and the
 * classification, the status read and the host store's resume reads all answer what the registered
 * commit records, as if the gnome had written nothing.
 */
class RegisterReadSpec extends Specification implements RegisteredRoundFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupRegisteredRound()
    }

    // FR13: the registered commit's shape, before the gnome writes anything — the baseline every
    //     feature below compares against.
    // Scenario: lifecycle/task-branch-contract / The tip equals the register
    def "FR13: a tip at the register classifies by the registered commit"() {
        expect:
        RefTipSource.at(runner, cloneDir, TASK, TIP_REF).tipRelation() == new TipRelation.AtRegister()
        shape() == new BranchShape.InProgress()
    }

    // F135: a Completed outcome the gnome commits above the register — alone, or with a matching
    //     state.json at the pipeline end — is never read: not by the classifier (no
    //     CompletedUncleaned, so no cleanup and no finish), not by status, not by the resume load.
    // Scenario: lifecycle/task-branch-contract / A gnome-written Completed outcome is not a pending cleanup
    // Scenario: lifecycle/task-branch-contract / A tip above the register is an open gnome range
    def "F135: a gnome-written Completed outcome above the register is not CompletedUncleaned (#variant)"() {
        when:
        gnomeRewrites('task.json') { it.outcome = [type: 'completed'] }
        if (withState) {
            gnomeRewrites('state.json') { it.position = [type: 'pipelineEnd'] }
        }

        then:
        RefTipSource.at(runner, cloneDir, TASK, TIP_REF).tipRelation() == new TipRelation.AboveRegister()
        shape() == new BranchShape.InProgress()
        store.readTaskRecord(worktree(), TASK).get().outcome() == null
        store.readRecordedState(worktree(), TASK).get().position() == new Position.AtStage('implement')
        ((branches.readState(cloneDir, TASK) as BranchStateResult.Found).report().outcome()) == null

        where:
        variant | withState
        'alone' | false
        'with a matching state.json' | true
    }

    // F135: a gate the gnome writes — an AwaitingApproval position under a paused outcome — opens
    //     nothing: the shape and the resume load are the registered commit's.
    // Scenario: lifecycle/task-branch-contract / A gnome-written gate position opens no gate
    def "F135: a gnome-written gate position above the register opens no gate"() {
        when:
        gnomeRewrites('state.json') {
            it.position = [type: 'awaitingApproval', stage: 'implement']
        }
        gnomeRewrites('task.json') {
            it.outcome = [type: 'paused', passedStage: 'implement']
        }

        then:
        shape() == new BranchShape.InProgress()
        store.readRecordedState(worktree(), TASK).get().position() == new Position.AtStage('implement')
        store.readTaskRecord(worktree(), TASK).get().outcome() == null
    }

    // F135: the pin a resume binds its law from is the registered commit's (ADR 0007's "the pin is
    //     final" by construction); a pin the gnome rewrote above the register is never read.
    // Scenario: lifecycle/task-branch-contract / A gnome-written base pin binds no law
    // Scenario: git/git-task-persistence / The pin is read at the register
    def "F135: a gnome-written base pin above the register is never read"() {
        given:
        def registered = store.readTaskRecord(worktree(), TASK).get().pin()

        when:
        gnomeRewrites('task.json') { it.baseRef = 'refs/heads/attacker' }

        then:
        store.readTaskRecord(worktree(), TASK).get().pin() == registered
        registered.ref() == 'HEAD'
    }
}
