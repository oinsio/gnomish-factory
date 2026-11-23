package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.agent.contract.HostRoundSources
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
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.TempDir

/**
 * FR18, M9 of extract-agent-round-runner (design D14): the git-mode host decoration, over the real
 * host source, passes the round-source port contract — its rounds open on the worktree's tip as
 * their token in the run's cell, and close and discard as the host source does.
 */
class MidRoundPushRoundsContractSpec extends RoundEnvironmentSourceContract implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    Path repo

    def setup() {
        repo = initTaskWorkingRepo(tempDir)
    }

    @Override
    protected RoundEnvironmentSource subject() {
        new MidRoundPushRounds(HostRoundSources.inPlace(), new GitProcessRunner(), new VirtualClock(), new CurrentRound())
    }

    @Override
    protected StageExecutor.Request roundRequest() {
        def stage = new StageDefinition(
                'implement', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(
                new TaskContext('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                stage, new DirectoryWorkspace(repo), 0, [])
    }

    @Override
    protected Optional<RoundToken> tokenAtOpen() {
        Optional.of(RoundToken.of(currentHead(repo)))
    }

    @Override
    protected boolean closeEnded(RoundEnvironmentSource.Round round, String scratchRoot) {
        !Files.exists(Path.of(scratchRoot))
    }

    @Override
    protected boolean environmentOutlivesRound(RoundEnvironmentSource.Round round) {
        false
    }
}
