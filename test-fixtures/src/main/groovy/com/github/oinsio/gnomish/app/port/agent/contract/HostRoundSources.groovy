package com.github.oinsio.gnomish.app.port.agent.contract

import com.github.oinsio.gnomish.adapter.agent.HostRoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment

/**
 * The real host round source, handed out as the port: {@code adapters/git}, which holds the
 * git-mode decoration {@code MidRoundPushRounds}, has no compile edge to {@code adapters/agent},
 * so its contract run builds the decoration over the host source through this fixture, as the
 * composition root does (task 5.1 of extract-agent-round-runner).
 */
final class HostRoundSources {

    private HostRoundSources() {
    }

    /** The undecorated host source the in-place run uses, on virtual time and the host allowlist. */
    static RoundEnvironmentSource inPlace() {
        new HostRoundEnvironmentSource(new VirtualClock(), TestChildEnvironment.hostAllowlist())
    }
}
