package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.BranchStateResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.TipRelation
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, D19 of tighten-spec-contract (design D13): the one fact the tip contributes is its relation
 * to the register. A closing write killed at any step leaves no closing commit above the register
 * (the commit and the register land in one ref transaction); a register the tip does not descend
 * from quarantines with both named, a missing one under a started branch is Unregistered (FR10 of
 * fix-terminal-receipt-convergence); and a pickup in
 * another clone reads at the register origin holds, fetched with the branch.
 */
class RegisterRelationSpec extends Specification implements RegisteredRoundFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupRegisteredRound()
    }

    // Design D13 as amended 2026-10-13: a closing commit and its register land in one ref
    //     transaction, so there is no step at which the branch names a factory closing commit the
    //     register does not. The outcome write is killed before each of its git invocations in turn;
    //     every kill leaves the branch and the register equal to each other, or both where they were.
    // Scenario: git/git-task-persistence / The register follows every closing commit
    def "FR13: a closing write killed before git invocation #n leaves branch and register together"() {
        given:
        def branchBefore = gitOutput(cloneDir, 'rev-parse', TIP_REF)
        def killing = new KillingGit(runner, n)

        when:
        boolean killed = false
        try {
            new GitTaskRepository(killing, registeredClone, ClaimEpochSource.NONE, new VirtualClock())
                    .recordOutcome(TASK, new TaskOutcome.Completed(TaskState.atStageStart('implement')), TrackerWrite.OWED)
        } catch (KilledProcess ignored) {
            killed = true
        }
        def branch = gitOutput(cloneDir, 'rev-parse', TIP_REF)
        def register = registeredId(cloneDir, TASK)

        then: 'no frozen state has a closing commit above the register'
        branch == register
        killed ? branch == branchBefore : branch != branchBefore

        and: 'the reader sees the same shape before the transaction and the new one after it'
        RefTipSource.at(runner, cloneDir, TASK, TIP_REF).tipRelation() == new TipRelation.AtRegister()

        where:
        n << (1..20)
    }

    /** The process dying at a chosen git invocation: thrown before that invocation runs. */
    static final class KilledProcess extends RuntimeException {}

    /** Real git, until the {@code killAt}-th invocation, which never runs. */
    static final class KillingGit implements GitRunner {

        private final GitRunner real
        private final int killAt
        private int calls

        KillingGit(GitRunner real, int killAt) {
            this.real = real
            this.killAt = killAt
        }

        private void tick() {
            if (++calls == killAt) {
                throw new KilledProcess()
            }
        }

        @Override
        GitCommandResult run(Path cwd, String... args) {
            tick()
            real.run(cwd, args)
        }

        @Override
        GitCommandResult run(Path cwd, com.github.oinsio.gnomish.gittransfer.GitTransfer transfer) {
            tick()
            real.run(cwd, transfer)
        }

        @Override
        GitCommandResult run(Path cwd, RefTransaction transaction) {
            tick()
            real.run(cwd, transaction)
        }
    }

    // D19: a register the branch tip does not descend from is quarantined with both ids named.
    // Scenario: lifecycle/task-branch-contract / A register that is not an ancestor of the tip quarantines
    def "D19: a register that is not an ancestor of the tip is Unknown naming both"() {
        given:
        def registered = registeredId(cloneDir, TASK)

        when: 'the branch is moved off the registered line, to its own base'
        assert runner.run(cloneDir, 'update-ref', TIP_REF, "${registered}~2").exitCode() == 0
        def tip = gitOutput(cloneDir, 'rev-parse', TIP_REF)

        then:
        def shape = shape()
        shape instanceof BranchShape.Unknown
        (shape as BranchShape.Unknown).reason().contains(registered)
        (shape as BranchShape.Unknown).reason().contains(tip)
        branches.readState(cloneDir, TASK) == new BranchStateResult.Shaped(shape)
    }

    // FR10 of fix-terminal-receipt-convergence (design D13): a started branch whose register is
    //     missing in this clone — a branch from before the register, one whose STARTED registration
    //     was killed, or one a third party's fetch brought — is Unregistered naming the register,
    //     never classified from the tip and never Unknown.
    // Scenario: lifecycle/task-branch-contract / A missing register is a state of the clone, not of the branch
    def "FR10: a started branch with no register is Unregistered naming the register and the tip"() {
        when:
        assert runner.run(cloneDir, 'update-ref', '-d', 'refs/gnomish-factory/PROJ-1').exitCode() == 0

        then:
        shape() == new BranchShape.Unregistered('refs/gnomish-factory/PROJ-1', gitOutput(cloneDir, 'rev-parse', TIP_REF))
        store.readTaskRecord(worktree(), TASK).isEmpty()
        store.readRecordedState(worktree(), TASK).isEmpty()
    }

    // FR13 on a bare origin: a pickup in another clone fetches the register with the branch, so the
    //     gnome commit a mid-round push carried to origin is above the register there too.
    // Scenario: git/git-task-persistence / The touchpoint fetch carries the register
    def "FR13: a pickup in a fresh clone classifies at the registered commit origin holds"() {
        given: 'origin holds the branch and the register; a mid-round push then carried a gnome commit'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        assert runner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main').exitCode() == 0
        new BestEffortClosingPush(runner).pushBestEffort(cloneDir, TASK)
        gnomeRewrites('task.json') { it.outcome = [type: 'completed'] }
        assert runner.run(cloneDir, 'push', 'origin', 'gnomish/PROJ-1').exitCode() == 0

        and: 'a peer clone that never saw the task'
        def peer = tempDir.resolve('peer')
        seedClone(tempDir, bare.toString(), peer, '--branch', 'main', '--single-branch')

        expect:
        branches.classifyShape(peer, TASK) == new BranchShape.InProgress()
        registeredId(peer, TASK) == registeredId(cloneDir, TASK)
    }

    // FR13 on a bare origin: a host mid-round push carries the branch alone, never the register, so
    //     a gnome commit that deletes the envelope is above origin's register and the pickup reads the
    //     registered commit — the envelope-free tip is never classified Bare.
    def "FR13: a gnome commit deleting the envelope, pushed mid-round, leaves a fresh pickup at the registered shape"() {
        given: 'origin holds the branch and the register'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        assert runner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main').exitCode() == 0
        new BestEffortClosingPush(runner).pushBestEffort(cloneDir, TASK)

        and: 'the gnome deletes the envelope and the mid-round push carries its commit'
        assert runner.run(worktree(), 'rm', '-r', '-q', '.gnomish-task').exitCode() == 0
        commitAll(worktree(), 'wip: drop the envelope')
        def gnomeTip = gitOutput(cloneDir, 'rev-parse', TIP_REF)
        BestEffortPush.midRound(runner).pushBestEffort(TASK, 'implement', 1, worktree(), 'gnomish/PROJ-1',
                new HostBranchProbe(runner, worktree(), 'gnomish/PROJ-1'), registeredId(cloneDir, TASK))
        assert gitOutput(bare, 'rev-parse', TIP_REF) == gnomeTip

        and: 'a peer clone that never saw the task'
        def peer = tempDir.resolve('peer')
        seedClone(tempDir, bare.toString(), peer, '--branch', 'main', '--single-branch')

        expect:
        branches.classifyShape(peer, TASK) == new BranchShape.InProgress()
        registeredId(peer, TASK) == registeredId(cloneDir, TASK)
    }
}
