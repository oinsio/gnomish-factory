package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentBinary
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path

/**
 * Shared seam for CLI-adapter specs that point {@link FactoryProperties} at
 * the fake agent binary (task 2, design D11 of add-agent-executor): {@code
 * FactoryProperties.agentCliBinary()} is a single token, but the fake's
 * script must be invoked as {@code sh <path>} (its executable bit is not
 * reliably preserved by Gradle's resource copy / a fresh checkout — see
 * {@code fake-agent/README.md}) and needs {@code GNOMISH_FAKE_SCENARIO} set
 * before it runs. This wraps both into one tiny generated shell script so a
 * real {@code CliStageExecutor} (running the round through the task environment
 * port) can invoke it as a plain binary path.
 *
 * <p>Also the single owner of the {@code StageExecutor.Request}/{@code TaskContext}
 * fixture shape every fake-agent-driven spec in {@code :adapters:agent} built by
 * hand ({@code requestFor}/{@code context()} were duplicated, byte for byte, across
 * four spec files — the rule-of-three trigger in {@code manual-sync-pairs.md}):
 * {@link #requestFor} and {@link #defaultTaskContext} are that one definition.
 *
 * <p>Not production code: test-support only, never PIT-mutated.
 */
final class FakeAgentSupport {

    /**
     * One wrapper file per distinct environment per JVM, not per call: macOS assesses a
     * freshly written executable on its FIRST direct exec (syspolicyd /
     * Gatekeeper), which can cost seconds — a per-call temp file made every
     * spawned round pay that scan cold, blowing tightly budgeted
     * PollingConditions windows in real-thread specs. The wrapper's content is
     * a pure function of the variables it exports, so reuse is safe.
     */
    private static final Map<Map<String, String>, String> WRAPPERS_BY_ENVIRONMENT = [:].asSynchronized()

    private FakeAgentSupport() {}

    /**
     * @param scenario the {@code GNOMISH_FAKE_SCENARIO} name to hardcode into
     *     the generated wrapper script
     * @return {@link FactoryProperties} whose {@code agentCliBinary} is the
     *     generated wrapper script's path
     */
    static FactoryProperties propertiesFor(String scenario) {
        propertiesOver(wrapperFor(scenario))
    }

    /**
     * One binary for both roles (FR6, D4 of remove-interactive-console): an invocation whose
     * {@code --model} is {@code judgeModel} plays {@code judgeScenario}, every other invocation
     * plays {@code scenario}.
     *
     * @param scenario the scenario an executor round plays
     * @param judgeModel the judge check's model id, which selects {@code judgeScenario}
     * @param judgeScenario the scenario a judge vote plays
     * @return {@link FactoryProperties} whose {@code agentCliBinary} is the generated wrapper
     */
    static FactoryProperties propertiesFor(String scenario, String judgeModel, String judgeScenario) {
        propertiesOver(wrapperFor(scenario, judgeModel, judgeScenario))
    }

    /**
     * The wrapper path itself, for a caller that hands the binary to a process rather than
     * building {@link FactoryProperties} in-JVM — the packaged-jar harness passes it as
     * {@code --factory.agent-cli-binary=<path>}. Same cache as {@link #propertiesFor}.
     *
     * @param scenario the scenario an executor round plays
     * @param judgeModel the judge check's model id, or {@code null} for an executor-only wrapper
     * @param judgeScenario the scenario a judge vote plays, or {@code null} with {@code judgeModel}
     * @return the absolute path of the generated wrapper script
     */
    static String wrapperFor(String scenario, String judgeModel = null, String judgeScenario = null) {
        if ((judgeModel == null) != (judgeScenario == null)) {
            throw new IllegalArgumentException('judgeModel and judgeScenario are set together or not at all')
        }
        Map<String, String> environment = [GNOMISH_FAKE_SCENARIO: scenario]
        if (judgeModel != null) {
            environment.GNOMISH_FAKE_JUDGE_MODEL = judgeModel
            environment.GNOMISH_FAKE_JUDGE_SCENARIO = judgeScenario
        }
        WRAPPERS_BY_ENVIRONMENT.computeIfAbsent(environment.asImmutable()) { Map<String, String> exports ->
            writeWrapper('fake-agent-wrapper', exports)
        }
    }

    /**
     * A wrapper that plays {@code judgeScenario} when the invocation's {@code --model} is
     * {@code judgeModel} and {@code executorScenario} otherwise, and appends every invocation's
     * argv to {@code argvCapture} through the fake's {@code GNOMISH_FAKE_CAPTURE_ARGV} hook — the
     * shape an E2E spec needs to drive an executor round and a judge vote through one binary and
     * then read back what each actually launched with (M1 of fix-operator-blockers). The wrapper
     * sets every variable itself, so none depends on the child-environment allowlist.
     *
     * @param executorScenario the scenario an executor round plays
     * @param judgeModel the judge check's model id, which selects {@code judgeScenario}
     * @param judgeScenario the scenario a judge vote plays
     * @param argvCapture the host file every invocation's argv is appended to
     * @return {@link FactoryProperties} whose {@code agentCliBinary} is the generated wrapper
     */
    static FactoryProperties propertiesCapturingArgv(
            String executorScenario, String judgeModel, String judgeScenario, Path argvCapture) {
        propertiesOver(writeWrapper('fake-agent-routing-wrapper', [
            GNOMISH_FAKE_SCENARIO : executorScenario,
            GNOMISH_FAKE_JUDGE_MODEL : judgeModel,
            GNOMISH_FAKE_JUDGE_SCENARIO: judgeScenario,
            GNOMISH_FAKE_CAPTURE_ARGV : argvCapture.toAbsolutePath().toString(),
        ]))
    }

    private static FactoryProperties propertiesOver(String wrapperPath) {
        new FactoryProperties('factory-01', wrapperPath, null, null)
    }

    private static String writeWrapper(String prefix, Map<String, String> exports) {
        def wrapper = File.createTempFile(prefix, '.sh')
        def lines = ['#!/bin/sh']
        exports.each { String name, String value ->
            lines << "export ${name}='${value}'".toString()
        }
        lines << "exec sh '${FakeAgentBinary.commandPrefix()[1]}' \"\$@\"".toString()
        wrapper.text = lines.join('\n') + '\n'
        wrapper.setExecutable(true)
        wrapper.deleteOnExit()
        wrapper.absolutePath
    }

    /**
     * Splits a {@code GNOMISH_FAKE_CAPTURE_ARGV} file into one argv per invocation, in launch
     * order (the fake terminates each invocation's block with a {@code ---} line).
     *
     * @param argvCapture the capture file
     * @return one argument list per captured invocation; empty when nothing was captured
     */
    static List<List<String>> capturedInvocations(Path argvCapture) {
        List<List<String>> invocations = []
        List<String> current = []
        argvCapture.toFile().readLines('UTF-8').each { String line ->
            if (line == '---') {
                invocations << current
                current = []
            } else {
                current << line
            }
        }
        invocations
    }

    /**
     * The {@code TaskContext} every fake-agent-driven spec builds for its round: a
     * fixed task id and untrusted-tracker title/body, no prior decisions.
     */
    static TaskContext defaultTaskContext() {
        new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    }

    /**
     * A one-stage {@code StageExecutor.Request} against {@code workspaceDir}, settings
     * merged into the {@code build} stage's executor settings — the fixture shape every
     * {@code CliStageExecutor} spec here drives a round through.
     *
     * @param workspaceDir the round's workspace, expected to already carry
     *     {@code instructions.md}
     * @param settings the executor settings for the {@code build} stage (e.g.
     *     {@code roundTimeout}); defaults to none
     */
    static StageExecutor.Request requestFor(Path workspaceDir, Map<String, Object> settings = [:]) {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', settings),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(defaultTaskContext(), stage, new DirectoryWorkspace(workspaceDir), 0, [])
    }
}
