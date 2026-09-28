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
     * One wrapper file per scenario per JVM, not per call: macOS assesses a
     * freshly written executable on its FIRST direct exec (syspolicyd /
     * Gatekeeper), which can cost seconds — a per-call temp file made every
     * spawned round pay that scan cold, blowing tightly budgeted
     * PollingConditions windows in real-thread specs. The wrapper's content is
     * a pure function of the scenario name, so reuse is safe.
     */
    private static final Map<String, String> WRAPPERS_BY_SCENARIO = [:].asSynchronized()

    private FakeAgentSupport() {}

    /**
     * @param scenario the {@code GNOMISH_FAKE_SCENARIO} name to hardcode into
     *     the generated wrapper script
     * @param envPassthrough the {@code agentCliEnvPassthrough} list (superseded
     *     config, ignored at runtime — see {@code FactoryProperties}); defaults
     *     to empty
     * @return {@link FactoryProperties} whose {@code agentCliBinary} is the
     *     generated wrapper script's path
     */
    static FactoryProperties propertiesFor(String scenario, List<String> envPassthrough = []) {
        def path = WRAPPERS_BY_SCENARIO.computeIfAbsent(scenario) { String name ->
            def wrapper = File.createTempFile('fake-agent-wrapper', '.sh')
            wrapper.text = "#!/bin/sh\nexport GNOMISH_FAKE_SCENARIO='${name}'\nexec sh '${FakeAgentBinary.commandPrefix()[1]}' \"\$@\"\n"
            wrapper.setExecutable(true)
            wrapper.deleteOnExit()
            wrapper.absolutePath
        }
        new FactoryProperties('factory-01', path, envPassthrough, null, null)
    }

    /**
     * A wrapper that plays {@code judgeScenario} when the invocation's {@code --model} is
     * {@code judgeModel} and {@code executorScenario} otherwise, and appends every invocation's
     * argv to {@code argvCapture} through the fake's {@code GNOMISH_FAKE_CAPTURE_ARGV} hook — the
     * shape an E2E spec needs to drive an executor round and a judge vote through one binary and
     * then read back what each actually launched with (M1 of fix-operator-blockers). The wrapper
     * sets both variables itself, so neither depends on the child-environment allowlist.
     *
     * @param executorScenario the scenario an executor round plays
     * @param judgeModel the judge check's model id, which selects {@code judgeScenario}
     * @param judgeScenario the scenario a judge vote plays
     * @param argvCapture the host file every invocation's argv is appended to
     * @return {@link FactoryProperties} whose {@code agentCliBinary} is the generated wrapper
     */
    static FactoryProperties propertiesCapturingArgv(
            String executorScenario, String judgeModel, String judgeScenario, Path argvCapture) {
        def wrapper = File.createTempFile('fake-agent-routing-wrapper', '.sh')
        wrapper.text = """\
#!/bin/sh
scenario='${executorScenario}'
previous=''
for arg in "\$@"; do
    if [ "\$previous" = '--model' ] && [ "\$arg" = '${judgeModel}' ]; then
        scenario='${judgeScenario}'
    fi
    previous="\$arg"
done
export GNOMISH_FAKE_SCENARIO="\$scenario"
export GNOMISH_FAKE_CAPTURE_ARGV='${argvCapture.toAbsolutePath()}'
exec sh '${FakeAgentBinary.commandPrefix()[1]}' "\$@"
"""
        wrapper.setExecutable(true)
        wrapper.deleteOnExit()
        new FactoryProperties('factory-01', wrapper.absolutePath, [], null, null)
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
