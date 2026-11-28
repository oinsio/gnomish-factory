package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.executorcontract.Policy
import spock.lang.Specification

/**
 * The argv fragment each abstract policy compiles to (FR3, FR5, D5 of extract-agent-round-runner):
 * {@code readOnly} → {@code dontAsk} with the read-only tool set that settings may only narrow,
 * {@code writeExactly} → {@code acceptEdits} with the pinpoint {@code Write} allowance, {@code
 * workspaceWrite} → {@code acceptEdits} with the settings passed through; {@code
 * --strict-mcp-config} always, and never the mode that skips every permission check.
 */
class ClaudePolicyCompilerSpec extends Specification {

    static final List<Policy> EVERY_POLICY = [
        new Policy.ReadOnly(),
        new Policy.WriteExactly('/scratch/answer.json'),
        new Policy.WorkspaceWrite()
    ]

    // Scenario: executor/claude-dialect / Verdict role is read-only
    def "FR5, D5: every policy carries its mode and --strict-mcp-config, never bypassPermissions — #policy"() {
        when:
        def fragment = ClaudePolicyCompiler.compile(policy, settings)

        then:
        fragment.takeRight(3) == [
            '--permission-mode',
            mode,
            '--strict-mcp-config'
        ]
        !fragment.contains('bypassPermissions')
        !fragment.contains('--mcp-config')

        where:
        [policy, settings] << [
            EVERY_POLICY,
            [
                [:],
                [allowedTools: ['Bash', 'Write'], disallowedTools: ['WebFetch'], permissionMode: 'bypassPermissions']
            ]
        ].combinations()
        mode = policy instanceof Policy.ReadOnly ? 'dontAsk' : 'acceptEdits'
    }

    def "D5: a readOnly policy with no allowedTools setting gets the whole read-only set and never acceptEdits"() {
        when:
        def fragment = ClaudePolicyCompiler.compile(new Policy.ReadOnly(), [:])

        then:
        fragment == [
            '--allowedTools',
            'Read,Grep,Glob',
            '--permission-mode',
            'dontAsk',
            '--strict-mcp-config'
        ]
        !fragment.contains('acceptEdits')
    }

    def "D5: settings that list only read-only tools narrow the set, in the settings' order"() {
        expect:
        ClaudePolicyCompiler.compile(new Policy.ReadOnly(), [allowedTools: requested]).take(2) == ['--allowedTools', effective]

        where:
        requested || effective
        ['Read'] || 'Read'
        ['Glob', 'Read']|| 'Glob,Read'
    }

    // Scenario: executor/claude-dialect / Settings cannot widen a read-only run
    def "D5: a write-capable tool in a readOnly run's settings is dropped from the effective set"() {
        expect:
        ClaudePolicyCompiler.compile(new Policy.ReadOnly(), [allowedTools: [
                'Read',
                'Write',
                'Bash(git:*)',
                'Grep'
            ]]) ==
        [
            '--allowedTools',
            'Read,Grep',
            '--permission-mode',
            'dontAsk',
            '--strict-mcp-config'
        ]
    }

    def "D5: settings naming only write-capable tools leave a readOnly run no allowed tool at all"() {
        expect:
        ClaudePolicyCompiler.compile(new Policy.ReadOnly(), [allowedTools: ['Write', 'Edit', 'Bash']]) ==
        [
            '--permission-mode',
            'dontAsk',
            '--strict-mcp-config'
        ]
    }

    def "FR5: a writeExactly policy appends the pinpoint Write allowance to whatever the settings allow"() {
        expect:
        ClaudePolicyCompiler.compile(new Policy.WriteExactly('/tmp/round-1/decision.json'), settings).take(2) ==
                ['--allowedTools', expected]

        where:
        settings || expected
        [:] || 'Write(/tmp/round-1/decision.json)'
        [allowedTools: []] || 'Write(/tmp/round-1/decision.json)'
        [allowedTools: ['Read', 'Grep']] || 'Read,Grep,Write(/tmp/round-1/decision.json)'
    }

    def "FR3: a workspaceWrite policy passes both tool lists through and omits an empty or absent one"() {
        expect:
        ClaudePolicyCompiler.compile(new Policy.WorkspaceWrite(), settings) ==
                expected + [
                    '--permission-mode',
                    'acceptEdits',
                    '--strict-mcp-config'
                ]

        where:
        settings || expected
        [:] || []
        [allowedTools: [], disallowedTools: []] || []
        [allowedTools: ['Edit', 'Write'], disallowedTools: ['Bash']] || [
            '--allowedTools',
            'Edit,Write',
            '--disallowedTools',
            'Bash'
        ]
    }

    def "FR3: disallowedTools passes through for a readOnly run too — it only ever narrows"() {
        expect:
        ClaudePolicyCompiler.compile(new Policy.ReadOnly(), [disallowedTools: ['Write', 'Edit']]) ==
        [
            '--allowedTools',
            'Read,Grep,Glob',
            '--disallowedTools',
            'Write,Edit',
            '--permission-mode',
            'dontAsk',
            '--strict-mcp-config'
        ]
    }
}
