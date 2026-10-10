package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared seam for CLI-adapter specs that point {@link FactoryProperties} at
 * the fake agent binary (task 2, design D11 of add-agent-executor): {@code
 * FactoryProperties.agentCliBinary()} is a single token, but the fake's
 * script must be invoked as {@code sh <path>} (see {@code fake-agent/README.md})
 * and needs {@code GNOMISH_FAKE_SCENARIO} set before it runs. One committed
 * stand-in preset does both for every scenario — {@code agent}, or
 * {@code agent-judged-by-<judgeModel>} for the two-role binary — reached through a
 * symbolic link named after the scenario, which the preset reads as
 * {@code GNOMISH_FAKE_SCENARIO}; so a real {@code CliStageExecutor} (running the
 * round through the task environment port) can invoke it as a plain binary path
 * (ADR 0015: a test never writes an executable file; the operating system assesses
 * the preset's script once per checkout, not once per spawned round). The links of
 * the plain and judged binaries live in one directory per JVM, one per scenario.
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

    /** One link per (preset, scenario) per JVM, named after the scenario; removed at exit. */
    private static final Map<String, String> LINKS = new ConcurrentHashMap<>()

    private static final Path LINK_ROOT = linkRoot()

    private FakeAgentSupport() {}

    /**
     * @param scenario the {@code GNOMISH_FAKE_SCENARIO} the agent plays
     * @return {@link FactoryProperties} whose {@code agentCliBinary} plays {@code scenario}
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
     * @return {@link FactoryProperties} whose {@code agentCliBinary} is the two-role binary
     */
    static FactoryProperties propertiesFor(String scenario, String judgeModel, String judgeScenario) {
        propertiesOver(wrapperFor(scenario, judgeModel, judgeScenario))
    }

    /**
     * The agent binary's path itself, for a caller that hands the binary to a process rather than
     * building {@link FactoryProperties} in-JVM — the packaged-jar harness passes it as
     * {@code --factory.agent-cli-binary=<path>}.
     *
     * @param scenario the scenario an executor round plays
     * @param judgeModel the judge check's model id, or {@code null} for an executor-only binary
     * @param judgeScenario the scenario a judge vote plays, or {@code null} with {@code judgeModel}
     * @return the absolute path of the scenario's link to the preset
     */
    static String wrapperFor(String scenario, String judgeModel = null, String judgeScenario = null) {
        String preset = presetFor(judgeModel, judgeScenario)
        LINKS.computeIfAbsent("${preset}/${scenario}".toString()) {
            Path dir = Files.createDirectories(LINK_ROOT.resolve(preset))
            dir.toFile().deleteOnExit()
            Path link = StandIn.link(dir.resolve(scenarioName(scenario)), preset)
            link.toFile().deleteOnExit()
            link.toString()
        }
    }

    /**
     * A binary that plays {@code judgeScenario} when the invocation's {@code --model} is
     * {@code judgeModel} and {@code executorScenario} otherwise, and records every invocation's
     * argv through the fake's {@code GNOMISH_FAKE_CAPTURE_ARGV} hook — the shape an E2E spec needs
     * to drive an executor round and a judge vote through one binary and then read back what each
     * actually launched with (M1 of fix-operator-blockers). The preset sets every variable itself,
     * so none depends on the child-environment allowlist. The binary is a per-run link named after
     * the executor scenario, beside {@code argvCapture}, whose log the capture is.
     *
     * @param executorScenario the scenario an executor round plays
     * @param judgeModel the judge check's model id, which selects {@code judgeScenario}
     * @param judgeScenario the scenario a judge vote plays
     * @param argvCapture the host file every invocation's argv lands in: {@code <executorScenario>.log}
     * @return {@link FactoryProperties} whose {@code agentCliBinary} is the per-run link
     */
    static FactoryProperties propertiesCapturingArgv(
            String executorScenario, String judgeModel, String judgeScenario, Path argvCapture) {
        propertiesOver(StandIn.link(linkBeside(argvCapture, executorScenario),
                "${presetFor(judgeModel, judgeScenario)}-capturing").toString())
    }

    /**
     * An agent binary playing {@code scenario} that appends every round's prompt — its stdin — to
     * {@code stdinCapture} through the fake's {@code GNOMISH_FAKE_CAPTURE_STDIN} hook, one block
     * per invocation closed by a {@code ---} line. The binary is a per-run link named after the
     * scenario, beside {@code stdinCapture}, whose log the capture is.
     *
     * @param scenario the scenario every round plays
     * @param stdinCapture the file the prompts land in: {@code <scenario>.log}
     * @return the per-run link's path, for {@code agentCliBinary}
     */
    static String binaryCapturingStdin(String scenario, Path stdinCapture) {
        StandIn.link(linkBeside(stdinCapture, scenario), 'agent-capturing-stdin').toString()
    }

    /** The per-run link named {@code scenario} whose log is {@code capture}, which must say so. */
    private static Path linkBeside(Path capture, String scenario) {
        String expected = "${scenarioName(scenario)}.log"
        if (capture.fileName.toString() != expected) {
            throw new IllegalArgumentException("an agent capture is named after its scenario: ${expected}, not ${capture.fileName}")
        }
        capture.resolveSibling(scenario)
    }

    /** {@code scenario} as a link name: the preset plays the name it is invoked under. */
    private static String scenarioName(String scenario) {
        if (scenario.isBlank() || scenario.contains('/')) {
            throw new IllegalArgumentException("a fake-agent scenario is a plain name, not '${scenario}'")
        }
        scenario
    }

    private static String presetFor(String judgeModel, String judgeScenario) {
        if ((judgeModel == null) != (judgeScenario == null)) {
            throw new IllegalArgumentException('judgeModel and judgeScenario are set together or not at all')
        }
        if (judgeModel != null && judgeScenario != 'judge-verdict-pass') {
            throw new IllegalArgumentException("no agent preset judges with ${judgeScenario}; add one to the stand-in library")
        }
        judgeModel == null ? 'agent' : "agent-judged-by-${judgeModel}"
    }

    private static Path linkRoot() {
        Path root = Files.createTempDirectory('fake-agent-links')
        root.toFile().deleteOnExit()
        root
    }

    private static FactoryProperties propertiesOver(String binaryPath) {
        new FactoryProperties('factory-01', binaryPath, null, null)
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
