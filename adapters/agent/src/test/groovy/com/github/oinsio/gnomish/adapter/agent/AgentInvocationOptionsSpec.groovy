package com.github.oinsio.gnomish.adapter.agent

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR11, FR12, D7 of add-agent-executor: {@code --model} always renders from
 * first-class manifest data; the four allowed settings keys render to their
 * CLI flags, omitted when absent — no settings validation here (task 9.1). Every row goes
 * through a role's own renderer: the role-less one is gone (FR1, FR2 of fix-operator-blockers).
 */
class AgentInvocationOptionsSpec extends Specification {

    private static final Path DECISION = Path.of('/tmp/round-1/decision.json')

    // FR11, D7: --model is first-class manifest data, always present and always first, for
    // both roles (FR1, FR2 of fix-operator-blockers: no role-less renderer exists).
    def "model always renders first as --model"() {
        expect:
        AgentInvocationOptions.renderForExecutor('claude-sonnet-4-5', [:], DECISION).take(2) == [
            '--model',
            'claude-sonnet-4-5'
        ]
        AgentInvocationOptions.renderForJudge('claude-sonnet-4-5', [:]).take(2) == [
            '--model',
            'claude-sonnet-4-5'
        ]
    }

    // FR11: a manifest allowedTools list renders as one comma-separated --allowedTools value.
    def "allowedTools renders as a comma-separated --allowedTools flag"() {
        when:
        def argv = AgentInvocationOptions.renderForExecutor('m', [allowedTools: ['Read', 'Grep', 'Bash(git:*)']], DECISION)

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Read,Grep,Bash(git:*),Write(/tmp/round-1/decision.json)'
        ]
    }

    // FR11: disallowedTools renders as a comma-separated --disallowedTools flag.
    def "disallowedTools renders as a comma-separated --disallowedTools flag"() {
        when:
        def argv = AgentInvocationOptions.renderForJudge('m', [disallowedTools: ['Write', 'Edit']])

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Read,Grep,Glob',
            '--disallowedTools',
            'Write,Edit'
        ]
    }

    // FR11: an absent or empty disallowedTools setting omits the flag entirely.
    def "disallowedTools flag is omitted when the key is absent or the list is empty"() {
        expect:
        !AgentInvocationOptions.renderForJudge('m', settings).contains('--disallowedTools')
        !AgentInvocationOptions.renderForExecutor('m', settings, DECISION).contains('--disallowedTools')

        where:
        settings << [
            [:],
            [disallowedTools: []]
        ]
    }

    // FR11: maxTurns renders as --max-turns <n>, accepting Integer or Long.
    def "maxTurns renders as --max-turns"() {
        when:
        def argv = AgentInvocationOptions.renderForJudge('m', [maxTurns: maxTurns])

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Read,Grep,Glob',
            '--max-turns',
            '5'
        ]

        where:
        maxTurns << [
            5,
            5L,
            5 as Integer,
            5 as Long
        ]
    }

    // FR11: an absent maxTurns key omits the flag entirely.
    def "maxTurns flag is omitted when the key is absent"() {
        expect:
        !AgentInvocationOptions.renderForJudge('m', [:]).contains('--max-turns')
        !AgentInvocationOptions.renderForExecutor('m', [:], DECISION).contains('--max-turns')
    }

    // FR11: roundTimeout is not a CLI flag (task 4.5) — never rendered, for either role.
    def "roundTimeout is never rendered as a CLI flag"() {
        expect:
        AgentInvocationOptions.renderForJudge('m', [roundTimeout: '30s']) == AgentInvocationOptions.renderForJudge('m', [:])
        AgentInvocationOptions.renderForExecutor('m', [roundTimeout: '30s'], DECISION) ==
        AgentInvocationOptions.renderForExecutor('m', [:], DECISION)
    }

    // FR11: all recognized settings keys together render in a stable order after --model.
    def "all recognized settings render together in order"() {
        when:
        def argv = AgentInvocationOptions.renderForJudge(
                'claude-sonnet-4-5',
                [
                    allowedTools: ['Read', 'Grep'],
                    disallowedTools: ['Write'],
                    maxTurns: 3,
                    roundTimeout: '30s'
                ])

        then:
        argv == [
            '--model',
            'claude-sonnet-4-5',
            '--allowedTools',
            'Read,Grep',
            '--disallowedTools',
            'Write',
            '--max-turns',
            '3'
        ]
    }

    // Defense in depth: an unrecognized key is ignored rather than crashing (validation
    // of unknown keys happens at startup, task 9.1 — not this renderer's job).
    def "unrecognized settings key is ignored"() {
        expect:
        AgentInvocationOptions.renderForJudge('m', [temperature: 0]) == AgentInvocationOptions.renderForJudge('m', [:])
        AgentInvocationOptions.renderForExecutor('m', [temperature: 0], DECISION) ==
        AgentInvocationOptions.renderForExecutor('m', [:], DECISION)
    }

    // FR12, NFR-S2, D7: the decision-file path gets a pinpoint Write allowance
    // even when the settings map has no allowedTools entry at all.
    def "renderForExecutor adds a pinpoint Write allowance with no allowedTools setting"() {
        when:
        def argv = AgentInvocationOptions.renderForExecutor('m', [:], Path.of('/tmp/round-1/decision.json'))

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Write(/tmp/round-1/decision.json)'
        ]
    }

    // FR12, NFR-S2, D7: an existing allowedTools setting is preserved and the
    // decision-file entry is appended after it.
    def "renderForExecutor appends the pinpoint Write allowance to an existing allowedTools setting"() {
        when:
        def argv = AgentInvocationOptions.renderForExecutor(
                'm',
                [allowedTools: ['Read', 'Grep']],
                Path.of('/tmp/round-1/decision.json'))

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Read,Grep,Write(/tmp/round-1/decision.json)'
        ]
    }

    // FR12, D7: other settings keys (disallowedTools, maxTurns) still render
    // through renderForExecutor exactly as they do through renderForJudge.
    def "renderForExecutor still renders disallowedTools and maxTurns"() {
        when:
        def argv = AgentInvocationOptions.renderForExecutor(
                'm',
                [disallowedTools: ['Bash'], maxTurns: 3],
                Path.of('/tmp/round-1/decision.json'))

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Write(/tmp/round-1/decision.json)',
            '--disallowedTools',
            'Bash',
            '--max-turns',
            '3'
        ]
    }

    // FR12, D7: renderForExecutor is hard-wired policy, not configurable — an
    // empty allowedTools list in settings still results in the pinpoint entry
    // being present, not an omitted flag.
    def "renderForExecutor never omits the allowedTools flag even with an empty list setting"() {
        when:
        def argv = AgentInvocationOptions.renderForExecutor(
                'm', [allowedTools: []], Path.of('/tmp/round-1/decision.json'))

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Write(/tmp/round-1/decision.json)'
        ]
    }

    // FR12, NFR-S1, D7: with no allowedTools setting at all, the judge gets the
    // full hard-wired read-only default.
    def "renderForJudge defaults to the hard-wired read-only tool set with no allowedTools setting"() {
        when:
        def argv = AgentInvocationOptions.renderForJudge('m', [:])

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Read,Grep,Glob'
        ]
    }

    // FR12, NFR-S1, D7: a manifest allowedTools subset of the read-only set is
    // preserved as-is — narrowing further is allowed, in manifest order.
    def "renderForJudge preserves a manifest allowedTools list that only narrows the read-only set"() {
        when:
        def argv = AgentInvocationOptions.renderForJudge('m', [allowedTools: ['Glob', 'Read']])

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Glob,Read'
        ]
    }

    // FR12, NFR-S1, D7, "Judge cannot widen its tools" scenario: a write-capable
    // tool requested in settings is silently dropped from the effective set.
    def "renderForJudge drops a write-capable tool requested in allowedTools"() {
        when:
        def argv = AgentInvocationOptions.renderForJudge('m', [allowedTools: [
                'Read',
                'Write',
                'Bash(git:*)',
                'Grep'
            ]])

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Read,Grep'
        ]
    }

    // FR12, NFR-S1, D7: when the manifest requests only write-capable tools, the
    // effective intersection is empty — as with any empty tool list, the
    // --allowedTools flag is omitted entirely rather
    // than rendered with an empty value.
    def "renderForJudge omits the allowedTools flag when the manifest requests only write-capable tools"() {
        when:
        def argv = AgentInvocationOptions.renderForJudge('m', [allowedTools: ['Write', 'Edit', 'Bash']])

        then:
        argv == ['--model', 'm']
    }

    // FR12, D7: disallowedTools and maxTurns still render through renderForJudge
    // exactly as they do through renderForExecutor — the read-only hard-wiring only
    // concerns allowedTools.
    def "renderForJudge still renders disallowedTools and maxTurns"() {
        when:
        def argv = AgentInvocationOptions.renderForJudge('m', [disallowedTools: ['Bash'], maxTurns: 3])

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Read,Grep,Glob',
            '--disallowedTools',
            'Bash',
            '--max-turns',
            '3'
        ]
    }
}
