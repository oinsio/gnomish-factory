package com.github.oinsio.gnomish.e2e.paidsmoke

import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import groovy.transform.CompileStatic

import java.nio.file.Path
/**
 * Shared launch of a real {@code claude -p --output-format stream-json --verbose} round through
 * the {@code TaskExecutionEnvironment} port, with the prompt delivered on stdin (FR24, D18 of
 * add-sandbox-core) — the exact command shape used both by {@link ClaudeLoginPreflight}'s login
 * check and {@link PaidSmokeReferenceDumpSpec}'s fixture-recording rounds.
 *
 * <p>Implements M4, D11 of add-agent-executor.
 *
 * <p>Statically compiled: the paid layer runs only by hand, so a production signature it calls
 * must break {@code compileTestGroovy} under {@code check}, not the next paid run
 * (task 6.2 of fix-operator-blockers).
 */
@CompileStatic
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
        launch([
            binary,
            '-p',
            '--output-format',
            'stream-json',
            '--verbose'
        ], workspaceRoot, clock, prompt)
    }

    /**
     * @param command the full argv, binary first
     * @param workspaceRoot an existing directory to run the round in
     * @param clock the clock passed to the execution environment
     * @param prompt the prompt delivered to the CLI on stdin
     * @return the launched process handle
     */
    static ExecHandle launch(List<String> command, Path workspaceRoot, SystemClock clock, String prompt) {
        def environment = new HostTaskExecutionEnvironment(workspaceRoot, clock, ChildEnvAllowlist.none())
        environment.exec(new ExecCommand(command, [:], prompt, false))
    }
}
