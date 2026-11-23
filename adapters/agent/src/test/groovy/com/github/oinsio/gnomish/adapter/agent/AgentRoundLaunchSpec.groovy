package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.claude.ClaudeEnvironment
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * What each role actually hands the environment port when it launches a round: the command line,
 * the stdin, and the environment of the {@link ExecCommand}, captured as the production consumer
 * builds it — the {@code claude} entry's stage half, the agent round runner with the Claude dialect,
 * through the shared round wrapper ({@link ClaudeStages}; task 5.1 of extract-agent-round-runner) — over a box that plays a
 * fake-agent scenario's stdout. Mode-independent by construction: the same command reaches the
 * host environment and the container environment alike, which is the point of the port.
 *
 * <p>FR1, FR8, FR9 of add-agent-executor; FR24, D18 of add-sandbox-core (prompt on stdin); FR5 of
 * fix-operator-blockers (the AI seam).
 */
class AgentRoundLaunchSpec extends Specification {

    @TempDir
    Path workspaceDir

    static final String CRITERIA = 'The output must be correct.'
    static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'Do the thing.', 'criteria.md': CRITERIA])
    static final FactoryProperties PROPERTIES = new FactoryProperties('factory-01', 'claude', null, null)

    final List<ExecCommand> launched = []

    /** The factory's values: every seam variable planted, beside a variable the seam never names. */
    static final Map<String, String> FACTORY_VALUES = ClaudeEnvironment.CREDENTIALS.collectEntries {
        [(it): "value-of-${it}".toString()]
    } + [ANTHROPIC_BASE_URL: 'https://gateway.example.test', AWS_SECRET_ACCESS_KEY: 'never']

    /** The run's allowlist over {@link #FACTORY_VALUES} — what the root binds to the secrets port. */
    final ChildEnvAllowlist childEnv = ChildEnvAllowlist.of([], [], TestChildEnvironment.valuesOf(FACTORY_VALUES))

    /** A box that records every launch and answers it with the scenario's recorded stdout. */
    private TaskExecutionEnvironment box(String scenario) {
        Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> { ExecCommand command ->
                launched << command
                new ExitedExecHandle(CannedOutputs.written(getClass().getResourceAsStream("/fake-agent/scenarios/${scenario}/stdout.jsonl")))
            }
        }
    }

    private ExecutionResult executorRound(StageExecutor.Request request) {
        ClaudeStages.executor(PROPERTIES, new VirtualClock(), LAW, { e -> } as AgentProgressListener, childEnv,
        StandInRound.over(box('plain-round'), workspaceDir.resolve('decision.json'))).execute(request)
    }

    private StageExecutor.Request retry(String finding) {
        def first = FakeAgentSupport.requestFor(workspaceDir)
        def feedback = [
            new CheckResult(new CheckRef(0, UntrustedText.manifest('command:make check')),
            new Verdict.Fail([
                new Finding(finding, null, null)
            ]), Duration.ZERO)
        ]
        new StageExecutor.Request(first.context(), first.stage(), first.workspace(), 1, feedback)
    }

    // FR1, D2: a retry is one more fresh process — no resume of the previous session — whose prompt
    //     carries the previous attempt's findings
    // Scenario: executor/agent-executor / Retry is a fresh process
    def "FR1: attempt 2 launches a new process with no session-resume flag, its prompt carrying attempt 1's findings"() {
        when:
        def result = executorRound(retry('tests are red in BuildSpec'))

        then: 'a new process was started through the round environment'
        (result.status() instanceof ExecutionResult.Completed && result.answer() == null)
        launched.size() == 1
        def argv = launched.first().command()
        [
            '--resume',
            '-r',
            '--continue',
            '-c',
            '--session-id',
            '--fork-session'
        ].every {
            !argv.contains(it)
        }

        and: 'the rendered findings of attempt 1 travel in its prompt'
        launched.first().stdin().contains('tests are red in BuildSpec')
    }

    // FR24, D18 of add-sandbox-core: the prompt and the findings ride stdin, the command line carries
    //     transport flags only
    // Scenario: executor/agent-executor / Prompt content is absent from the command line
    def "FR24: an executor round carries no prompt text on its command line"() {
        when:
        executorRound(retry('a finding the command line must not show'))

        then:
        launched.size() == 1
        launched.every { command ->
            def line = command.command().join(' ')
            command.stdin() != null && !command.stdin().isBlank() &&
                    !line.contains('a finding the command line must not show') && !line.contains(CRITERIA) &&
                    !line.contains('Do the thing.') && !line.contains('Task goal')
        }
    }

    // FR5 of fix-operator-blockers: both roles hand the port the seam the factory's values hold;
    //     the five names are pinned by ClaudeEnvironmentSpec. NFR-S3 of tighten-spec-contract: the values
    //     come from the run's allowlist (the secrets port, in production), planted here, never from
    //     this process's environment — so every name is present and the comparison is not vacuous
    // Scenario: executor/agent-executor / The AI seam carries five variables
    def "FR5: an agent round receives the AI seam with its current values"() {
        given:
        def seam = FACTORY_VALUES.subMap(ClaudeEnvironment.CREDENTIALS)

        when:
        executorRound(FakeAgentSupport.requestFor(workspaceDir))

        then: 'the seam is the five names the factory sets, and each launch carries every one present'
        ClaudeEnvironment.CREDENTIALS as Set == [
            'CLAUDE_CODE_OAUTH_TOKEN',
            'ANTHROPIC_API_KEY',
            'ANTHROPIC_BASE_URL',
            'ANTHROPIC_AUTH_TOKEN',
            'ANTHROPIC_MODEL'
        ] as Set
        launched.size() == 1
        seam.size() == 5
        launched.every {
            it.env().subMap(ClaudeEnvironment.CREDENTIALS) == seam && !it.env().containsKey('AWS_SECRET_ACCESS_KEY')
        }
    }
}
