package com.github.oinsio.gnomish.adapter.agent

import spock.lang.Specification

/**
 * FR11, FR12, D7 of add-agent-executor: {@code --model} always renders from
 * first-class manifest data; the four allowed settings keys render to their
 * CLI flags, omitted when absent — no settings validation here (task 9.1).
 */
class AgentInvocationOptionsSpec extends Specification {

    // FR11, D7: --model is first-class manifest data, always present.
    def "model always renders as --model"() {
        when:
        def argv = AgentInvocationOptions.render('claude-sonnet-4-5', [:])

        then:
        argv == [
            '--model',
            'claude-sonnet-4-5'
        ]
    }

    // FR11: allowedTools renders as a single comma-separated --allowedTools flag.
    def "allowedTools renders as a comma-separated --allowedTools flag"() {
        when:
        def argv = AgentInvocationOptions.render('m', [allowedTools: ['Read', 'Grep', 'Bash(git:*)']])

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Read,Grep,Bash(git:*)'
        ]
    }

    // FR11: an absent allowedTools key omits the flag entirely.
    def "allowedTools flag is omitted when the key is absent"() {
        when:
        def argv = AgentInvocationOptions.render('m', [:])

        then:
        argv == ['--model', 'm']
    }

    // FR11: an empty allowedTools list also omits the flag.
    def "allowedTools flag is omitted when the list is empty"() {
        when:
        def argv = AgentInvocationOptions.render('m', [allowedTools: []])

        then:
        argv == ['--model', 'm']
    }

    // FR11: disallowedTools renders as a comma-separated --disallowedTools flag.
    def "disallowedTools renders as a comma-separated --disallowedTools flag"() {
        when:
        def argv = AgentInvocationOptions.render('m', [disallowedTools: ['Write', 'Edit']])

        then:
        argv == [
            '--model',
            'm',
            '--disallowedTools',
            'Write,Edit'
        ]
    }

    // FR11: an absent disallowedTools key omits the flag entirely.
    def "disallowedTools flag is omitted when the key is absent"() {
        when:
        def argv = AgentInvocationOptions.render('m', [:])

        then:
        argv == ['--model', 'm']
    }

    // FR11: maxTurns renders as --max-turns <n>, accepting Integer or Long.
    def "maxTurns renders as --max-turns"() {
        when:
        def argv = AgentInvocationOptions.render('m', [maxTurns: maxTurns])

        then:
        argv == [
            '--model',
            'm',
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
        when:
        def argv = AgentInvocationOptions.render('m', [:])

        then:
        argv == ['--model', 'm']
    }

    // FR11: roundTimeout is out of scope for this task (task 4.5) — never rendered.
    def "roundTimeout is never rendered as a CLI flag"() {
        when:
        def argv = AgentInvocationOptions.render('m', [roundTimeout: '30s'])

        then:
        argv == ['--model', 'm']
    }

    // FR11: all four settings keys together render in a stable order after --model.
    def "all recognized settings render together in order"() {
        when:
        def argv = AgentInvocationOptions.render(
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

    // FR11: an empty settings map renders only --model.
    def "empty settings map renders only --model"() {
        when:
        def argv = AgentInvocationOptions.render('m', [:])

        then:
        argv == ['--model', 'm']
    }

    // Defense in depth: an unrecognized key is ignored rather than crashing (validation
    // of unknown keys happens at startup, task 9.1 — not this renderer's job).
    def "unrecognized settings key is ignored"() {
        when:
        def argv = AgentInvocationOptions.render('m', [temperature: 0])

        then:
        argv == ['--model', 'm']
    }

    // FR12, NFR-S2, D7: the decision-file path gets a pinpoint Write allowance
    // even when the settings map has no allowedTools entry at all.
    def "renderForExecutor adds a pinpoint Write allowance with no allowedTools setting"() {
        when:
        def argv = AgentInvocationOptions.renderForExecutor('m', [:], java.nio.file.Path.of('/tmp/round-1/decision.json'))

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
                java.nio.file.Path.of('/tmp/round-1/decision.json'))

        then:
        argv == [
            '--model',
            'm',
            '--allowedTools',
            'Read,Grep,Write(/tmp/round-1/decision.json)'
        ]
    }

    // FR12, D7: other settings keys (disallowedTools, maxTurns) still render
    // through renderForExecutor exactly as they do through render().
    def "renderForExecutor still renders disallowedTools and maxTurns"() {
        when:
        def argv = AgentInvocationOptions.renderForExecutor(
                'm',
                [disallowedTools: ['Bash'], maxTurns: 3],
                java.nio.file.Path.of('/tmp/round-1/decision.json'))

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
                'm', [allowedTools: []], java.nio.file.Path.of('/tmp/round-1/decision.json'))

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
    // effective intersection is empty — consistent with render()'s existing
    // empty-list handling, the --allowedTools flag is omitted entirely rather
    // than rendered with an empty value.
    def "renderForJudge omits the allowedTools flag when the manifest requests only write-capable tools"() {
        when:
        def argv = AgentInvocationOptions.renderForJudge('m', [allowedTools: ['Write', 'Edit', 'Bash']])

        then:
        argv == ['--model', 'm']
    }

    // FR12, D7: disallowedTools and maxTurns still render through renderForJudge
    // exactly as they do through render() — the read-only hard-wiring only
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
