package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.agent.contract.RoundEnvironmentSourceContract
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.TempDir

/**
 * FR18, M9 of extract-agent-round-runner (design D14): the in-place host round source passes the
 * round-source port contract — its rounds carry the in-place identity, and its close and discard
 * each dispose the environment it materialized for the round.
 */
class HostRoundEnvironmentSourceContractSpec extends RoundEnvironmentSourceContract {

    @TempDir
    Path workspaceDir

    @Override
    protected RoundEnvironmentSource subject() {
        new HostRoundEnvironmentSource(new VirtualClock(), TestChildEnvironment.hostAllowlist())
    }

    @Override
    protected StageExecutor.Request roundRequest() {
        StageExecutorRequests.request(workspaceDir)
    }

    @Override
    protected Optional<RoundToken> tokenAtOpen() {
        Optional.empty()
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
