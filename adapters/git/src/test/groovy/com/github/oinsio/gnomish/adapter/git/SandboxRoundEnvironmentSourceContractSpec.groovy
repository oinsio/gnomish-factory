package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.agent.contract.RoundEnvironmentSourceContract
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.TempDir

/**
 * FR18, M9 of extract-agent-round-runner (design D14): the box round source, over an in-process box
 * on a real factory clone, passes the round-source port contract — its rounds open on the task
 * branch's tip as their token, close with the snapshot recorded in the run's cell, and leave the
 * leased box to its lease on a discard.
 */
class SandboxRoundEnvironmentSourceContractSpec extends RoundEnvironmentSourceContract implements BareGitRepoFixture {

    static final String TASK = 'SBX-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)
    static final String STAGE = 'build'

    @TempDir
    Path tempDir

    Path cloneDir
    CurrentRound rounds = new CurrentRound()
    EnvironmentLease lease

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        // The branch starts at a registered closing commit, as STARTED leaves it (design D13).
        gitOutput(cloneDir, 'update-ref', 'refs/gnomish-factory/' + BRANCH.substring('gnomish/'.length()), 'refs/heads/' + BRANCH)
        def boxes = 0
        lease = new EnvironmentLease({
            ->
            def boxRoot = Files.createDirectories(tempDir.resolve('box' + (boxes++)))
            new LocalBoxEnvironment(cloneDir, boxRoot)
        },
        BRANCH,
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ])
    }

    private static StageDefinition stage() {
        new StageDefinition(
                STAGE, 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    @Override
    protected RoundEnvironmentSource subject() {
        new SandboxRoundEnvironmentSource(lease, new GitProcessRunner(), cloneDir, TASK, rounds, new VirtualClock())
    }

    @Override
    protected StageExecutor.Request roundRequest() {
        new StageExecutor.Request(
                new TaskContext(TASK, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                stage(), new DirectoryWorkspace(tempDir), 1, [])
    }

    @Override
    protected Optional<RoundToken> tokenAtOpen() {
        Optional.of(RoundToken.of(gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)))
    }

    /** The box round's close is its snapshot, recorded in the run's cell under the token it opened on. */
    @Override
    protected boolean closeEnded(RoundEnvironmentSource.Round round, String scratchRoot) {
        rounds.snapshot().map { it.token() } == Optional.of(rounds.opened())
    }

    /** A commit on the task branch in the factory clone, the tip a box round's token is minted from. */
    @Override
    protected void moveTip() {
        def tip = 'refs/heads/' + BRANCH
        def moved = gitOutput(cloneDir, 'commit-tree', '-p', tip, '-m', 'moved', tip + '^{tree}')
        gitOutput(cloneDir, 'update-ref', tip, moved)
    }

    /** The box is the lease's for the stage: its files die with the box, not with the round. */
    @Override
    protected boolean environmentOutlivesRound(RoundEnvironmentSource.Round round) {
        round.environment().is(lease.environmentFor(STAGE))
    }
}
