package com.github.oinsio.gnomish.e2e.paidsmoke

import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import java.nio.file.Path

/**
 * Shared launch of a real {@code claude -p --output-format stream-json --verbose} round through
 * the {@code TaskExecutionEnvironment} port, with the prompt delivered on stdin (FR24, D18 of
 * add-sandbox-core) — the exact command shape used both by {@link ClaudeLoginPreflight}'s login
 * check and {@link PaidSmokeReferenceDumpSpec}'s fixture-recording rounds.
 *
 * <p>Implements M4, D11 of add-agent-executor.
 */
final class PaidSmokeAgentLauncher {

    private PaidSmokeAgentLauncher() {}

    /**
     * @param binary the CLI binary name or path to run
     * @param workspaceRoot an existing directory to run the round in
     * @param clock the clock passed to the execution environment
     * @param prompt the prompt delivered to the CLI on stdin
     * @return the launched process handle
     */
    static ExecHandle launch(String binary, Path workspaceRoot, SystemClock clock, String prompt) {
        def environment = new HostTaskExecutionEnvironment(workspaceRoot, clock, ChildEnvAllowlist.none())
        def command = [
            binary,
            '-p',
            '--output-format',
            'stream-json',
            '--verbose'
        ]
        environment.exec(new ExecCommand(command, [:], prompt, false))
    }
}
