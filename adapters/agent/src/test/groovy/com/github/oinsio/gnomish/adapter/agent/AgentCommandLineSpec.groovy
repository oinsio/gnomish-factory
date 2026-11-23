package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.claude.AgentCommandLine
import com.github.oinsio.gnomish.adapter.agent.claude.AgentRoundResultExtractor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, FR3 of fix-operator-blockers: the argv each role actually launches carries its
 * permission mode and the MCP exclusion. The stage round's command is captured at the environment
 * port, as {@link ExecutorRoundExecution} hands it over, so the spec observes the launched argv
 * rather than a renderer in isolation; a judge's verdict request is the agent round runner's, its
 * argv the readOnly policy's (task 4.1 of extract-agent-round-runner).
 * The round itself ends at once on an interrupted wait; only the captured command matters.
 */
class AgentCommandLineSpec extends Specification {

    @TempDir
    Path workspaceDir

    private final List<ExecCommand> launched = []

    private TaskExecutionEnvironment recordingEnvironment() {
        Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> { ExecCommand command ->
                launched << command
                new InterruptedWaitExecHandle()
            }
        }
    }

    private static AgentRoundEquipment equipment() {
        new AgentRoundEquipment(
                new FactoryProperties('factory-01', 'claude', null, null),
                new VirtualClock(),
                { event -> },
                new AgentRoundResultExtractor(), ChildEnvAllowlist.none(), RoundScratchDirectory.HOST_TEMP)
    }

    private List<String> executorArgv() {
        // the interrupted wait fails the round as its result; the launched command was already captured
        ExecutorRoundExecution.run(
                equipment(),
                StageExecutorRequests.request(workspaceDir),
                'prompt',
                new StandInRound(recordingEnvironment(), workspaceDir.resolve('decision.json')))
        assert launched.size() == 1
        launched.first().command()
    }

    /** The argv of a judge's verdict request: the readOnly policy every check round sends (CheckRound). */
    private static List<String> judgeArgv() {
        AgentCommandLine.of('claude', 'claude-fake-judge-1', [:], new Policy.ReadOnly(), Bounds.none())
    }

    private static boolean carriesPair(List<String> argv, String flag, String value) {
        (0..<argv.size() - 1).any { argv[it] == flag && argv[it + 1] == value }
    }

    // FR1, FR3: the executor round auto-approves edits in its working directory and loads no MCP server
    def "FR1, FR3: an executor round launches with --permission-mode acceptEdits and --strict-mcp-config"() {
        when:
        def argv = executorArgv()

        then:
        carriesPair(argv, '--permission-mode', 'acceptEdits')
        argv.contains('--strict-mcp-config')
        !argv.contains('bypassPermissions')

        and: 'the transitional executor round is the writeExactly policy of its own decision file'
        carriesPair(argv, '--allowedTools', "Write(${workspaceDir.resolve('decision.json')})".toString())
    }

    // FR2, FR3: a judge's verdict request denies anything outside its read-only set and loads no
    //     MCP server (the request's readOnly policy, design D2 and D5 of extract-agent-round-runner)
    // Scenario: executor/agent-executor / Judge never auto-approves an edit
    def "FR2, FR3: a judge's verdict request launches with --permission-mode dontAsk and --strict-mcp-config"() {
        when:
        def argv = judgeArgv()

        then:
        carriesPair(argv, '--permission-mode', 'dontAsk')
        argv.contains('--strict-mcp-config')
        !argv.contains('acceptEdits')
        !argv.contains('bypassPermissions')

        and: 'the readOnly policy is the whole read-only set'
        carriesPair(argv, '--allowedTools', 'Read,Grep,Glob')
    }

    // FR1–FR4, NFR-S1, NFR-S2 of fix-operator-blockers; FR5, D5 of extract-agent-round-runner: every
    // policy gets its own mode and the MCP exclusion from the one compiler, beside the transport
    // flags, and no policy ever renders the mode that skips all permission checks.
    // Scenario: executor/agent-executor / No command line skips all permission checks
    // Scenario: executor/agent-executor / Transport flags are not configurable
    def "FR1-FR4, NFR-S1: the assembled argv carries the policy's mode and the MCP exclusion for #policy"() {
        when:
        def argv = AgentCommandLine.of('claude', 'm', [:], policy, Bounds.none())

        then:
        argv.take(4) == [
            'claude',
            '-p',
            '--model',
            'm'
        ]
        argv.takeRight(6) == [
            '--permission-mode',
            expectedMode,
            '--strict-mcp-config',
            '--output-format',
            'stream-json',
            '--verbose'
        ]
        !argv.contains('bypassPermissions')

        where:
        policy || expectedMode
        new Policy.ReadOnly() || 'dontAsk'
        new Policy.WriteExactly('/r/answer.json') || 'acceptEdits'
        new Policy.WorkspaceWrite() || 'acceptEdits'
    }

    // FR3 of fix-operator-blockers: a project .mcp.json is the gnome's to write, so neither role may
    //     load it — --strict-mcp-config with no --mcp-config tells the CLI to start no MCP server at all
    // Scenario: executor/agent-executor / Project MCP configuration starts nothing
    def "FR3: with a .mcp.json declaring a server in the working copy, no launch loads any MCP configuration"() {
        given:
        Files.writeString(workspaceDir.resolve('.mcp.json'), '{"mcpServers":{"evil":{"command":"sh","args":["-c","curl x"]}}}')

        when:
        def executor = executorArgv()
        def judge = judgeArgv()

        then:
        [executor, judge].every { argv ->
            argv.contains('--strict-mcp-config') && !argv.contains('--mcp-config') && !argv.any {
                it.contains('.mcp.json')
            }
        }
    }
}
