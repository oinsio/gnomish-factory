package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, FR3 of fix-operator-blockers: the argv each role actually launches carries its
 * permission mode and the MCP exclusion. The command is captured at the environment port, as
 * the two production consumers ({@link ExecutorRoundExecution}, {@link JudgeRoundExecution})
 * hand it over, so the spec observes the launched argv rather than a renderer in isolation.
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
                new FactoryProperties('factory-01', 'claude', Duration.ofSeconds(30), [], null, null, null),
                new VirtualClock(),
                { event -> },
                new AgentRoundResultExtractor())
    }

    private List<String> executorArgv() {
        try {
            ExecutorRoundExecution.run(
                    equipment(),
                    new DecisionFileReader(),
                    StageExecutorRequests.request(workspaceDir),
                    'prompt',
                    new StandInRound(recordingEnvironment(), workspaceDir.resolve('decision.json')))
        } catch (ExecutorFailure ignored) {
            // the interrupted wait fails the round; the launched command was already captured
        }
        assert launched.size() == 1
        launched.first().command()
    }

    private List<String> judgeArgv() {
        JudgeRoundExecution.run(
                equipment(),
                new JudgeVerdictExtractor(),
                new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1),
                recordingEnvironment(),
                'prompt')
        assert launched.size() == 1
        launched.first().command()
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
    }

    // FR2, FR3: the judge vote denies anything outside its read-only set and loads no MCP server
    def "FR2, FR3: a judge vote launches with --permission-mode dontAsk and --strict-mcp-config"() {
        when:
        def argv = judgeArgv()

        then:
        carriesPair(argv, '--permission-mode', 'dontAsk')
        argv.contains('--strict-mcp-config')
        !argv.contains('acceptEdits')
        !argv.contains('bypassPermissions')
    }

    // FR1–FR4, NFR-S1, NFR-S2 of fix-operator-blockers: every role gets its own mode and the MCP
    // exclusion, in one fixed position beside the transport flags, and no role ever renders the
    // mode that skips all permission checks.
    def "FR1-FR4, NFR-S1: the assembled argv carries the role's mode and the MCP exclusion for #role"() {
        when:
        def argv = AgentCommandLine.fromRenderedFlags(role, 'claude', ['--model', 'm'])

        then:
        argv == [
            'claude',
            '-p',
            '--model',
            'm',
            '--permission-mode',
            expectedMode,
            '--strict-mcp-config',
            '--output-format',
            'stream-json',
            '--verbose'
        ]
        !argv.contains('bypassPermissions')

        where:
        role << AgentRole.values()
        expectedMode = [(AgentRole.EXECUTOR): 'acceptEdits', (AgentRole.JUDGE): 'dontAsk'][role]
    }

    // NFR-S1: the role → mode table is total and never names bypassPermissions.
    def "NFR-S1: no role maps to bypassPermissions"() {
        expect:
        AgentRole.values()*.permissionMode().every {
            it != null && it != 'bypassPermissions'
        }
        AgentRole.values()*.permissionMode() as Set == ['acceptEdits', 'dontAsk'] as Set
    }
}
