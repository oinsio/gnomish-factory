package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The command line and the stdin prompt {@link ClaudeDialect} renders for a request, per policy
 * (FR2, FR3, FR5, NFR-C1 of extract-agent-round-runner): the moved {@link AgentCommandLine} behind
 * the dialect, its tool sets and mode compiled by {@link ClaudePolicyCompiler}, with the prompt on stdin and never in the argv.
 *
 * <p>FR2, FR3 of extract-agent-round-runner.
 */
class ClaudeDialectRenderSpec extends Specification {

    static final String TRANSPORT = '--strict-mcp-config --output-format stream-json --verbose'

    def dialect = new ClaudeDialect('claude', UntrustedText.subprocess('2.1.0'), 'api.anthropic.com')

    def "FR3: a workspaceWrite request runs in acceptEdits with its settings passed through and no write allowance"() {
        when:
        def rendered = dialect.render(request(Role.TRANSFORM, new Policy.WorkspaceWrite(), RoleInputs.transform('build it'),
                [model: 'opus', allowedTools: ['Bash'], disallowedTools: ['WebFetch'], maxTurns: 7, roundTimeout: '5m']))

        then: 'the prompt travels on stdin, never in the argv'
        rendered.argv().join(' ') == 'claude -p --model opus --max-turns 7 --allowedTools Bash --disallowedTools WebFetch' +
                " --permission-mode acceptEdits ${TRANSPORT}"
        rendered.stdin() == 'build it'
    }

    // Scenario: executor/claude-dialect / Verdict role is read-only
    // Scenario: executor/claude-dialect / Settings cannot widen a read-only run
    def "FR3: a readOnly request runs in dontAsk, and its settings only narrow the read-only tool set"() {
        when:
        def rendered = dialect.render(request(Role.VERDICT, new Policy.ReadOnly(), RoleInputs.verdict('is it built?'),
                [model: 'opus', allowedTools: ['Read', 'Write']]))

        then:
        rendered.argv().join(' ') == "claude -p --model opus --allowedTools Read --permission-mode dontAsk ${TRANSPORT}"
        rendered.stdin() == 'is it built?'
        !rendered.argv().contains('acceptEdits')
        !rendered.argv().contains('bypassPermissions')
    }

    def "FR3: a writeExactly request runs in acceptEdits with the one path allowed"() {
        when:
        def rendered = dialect.render(request(Role.DECISION, new Policy.WriteExactly('/scratch/answer.json'),
                RoleInputs.decision('which way?', ['left', 'right']), [model: 'opus']))

        then: 'the decision prompt lists its options under the question'
        rendered.argv().join(' ') == 'claude -p --model opus --allowedTools Write(/scratch/answer.json)' +
                " --permission-mode acceptEdits ${TRANSPORT}"
        rendered.stdin() == 'which way?\n- left\n- right'
    }

    def "FR3: a request with no model setting and no role input renders an empty model and an empty prompt"() {
        when:
        def rendered = dialect.render(request(Role.EFFECT, new Policy.WorkspaceWrite(), RoleInputs.none(), [:]))

        then:
        rendered.argv().take(4) == ['claude', '-p', '--model', '']
        rendered.stdin() == ''
    }

    // NFR-C1: the request's bounds are hints the CLI is handed; the ledger enforces them after the round
    // Scenario: executor/claude-dialect / Turn and budget hints reach the argv
    def "NFR-C1: a request's turn and budget bounds reach the argv as --max-turns and --max-budget-usd"() {
        when:
        def rendered = dialect.render(request(Role.TRANSFORM, new Policy.WorkspaceWrite(), RoleInputs.transform('build it'),
                [model: 'opus'], new Bounds(40L, 100000L, new BigDecimal('2.5'), null, null, null)))

        then:
        rendered.argv().join(' ') == "claude -p --model opus --max-turns 40 --max-budget-usd 2.5" +
                " --permission-mode acceptEdits ${TRANSPORT}"
    }

    // NFR-S1: whatever the settings say — a permissionMode key included — no policy renders the
    //     mode that skips every permission check
    def "NFR-S1: no rendered argv carries bypassPermissions, whatever the policy and the settings"() {
        expect:
        [
            new Policy.ReadOnly(),
            new Policy.WriteExactly('/a.json'),
            new Policy.WorkspaceWrite()
        ].every { policy ->
            def argv = dialect.render(request(Role.TRANSFORM, policy, RoleInputs.transform('x'),
            [model: 'opus', permissionMode: 'bypassPermissions'])).argv()
            argv.contains('--strict-mcp-config') && !argv.contains('bypassPermissions') && argv.count('--permission-mode') == 1
        }
    }

    private static ExecutorRequest request(Role role, Policy policy, RoleInputs inputs, Map<String, Object> settings,
            Bounds bounds = Bounds.none()) {
        new ExecutorRequest(role, new Workspace('.'), policy, inputs, ExecutorContractJson.settingsTree(settings),
                bounds, [], null)
    }
}
