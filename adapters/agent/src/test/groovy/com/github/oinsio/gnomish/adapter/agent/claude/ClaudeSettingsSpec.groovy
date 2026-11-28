package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.executorcontract.SettingsJudgement
import com.github.oinsio.gnomish.executorcontract.SettingsSchema
import spock.lang.Specification

/**
 * FR7, D6 of extract-agent-round-runner: the Claude dialect's settings schema holds exactly the keys
 * the dialect consumes, judged through the contract's one settings judge, with the {@code
 * roundTimeout} shapes left to their one owner; every violation is a located error at the key.
 *
 * <p>FR11, UX2, D7 of add-agent-executor (the keys and shapes accepted before the schema moved here).
 */
class ClaudeSettingsSpec extends Specification {

    private static final String FILE = 'stages/build/stage.yaml'
    private static final String WHERE = 'executor.settings'

    def "the schema is usable by the contract's judge and accepts every key in its shape"() {
        given:
        def settings = [allowedTools: ['Read', 'Write'], disallowedTools: ['Bash'], maxTurns: 5, roundTimeout: 30]

        expect:
        SettingsSchema.judge(ClaudeSettings.schema(), settings) instanceof SettingsJudgement.Accepted
        ClaudeSettings.validate(FILE, WHERE, settings) == []
        ClaudeSettings.validate(FILE, WHERE, [:]) == []
    }

    def "each call hands out its own schema node"() {
        expect:
        !ClaudeSettings.schema().is(ClaudeSettings.schema())
        ClaudeSettings.schema() == ClaudeSettings.schema()
    }

    // FR4 of fix-operator-blockers, FR11 of add-agent-executor: the permission mode, the MCP
    //     configuration, the environment allowlist and the model are not settings
    def "a key the dialect does not consume (#key) is a located error at the key"() {
        when:
        def errors = ClaudeSettings.validate(FILE, WHERE, [(key): value])

        then:
        errors.size() == 1
        errors[0].file() == FILE
        errors[0].where() == "${WHERE}.${key}".toString()
        errors[0].message().contains("rule 'additionalProperties'")

        where:
        key | value
        'allowedTols' | ['Read']
        'permissionMode' | 'bypassPermissions'
        'strictMcpConfig' | false
        'mcpConfig' | '.mcp.json'
        'envAllowlist' | ['PATH']
        'envPassthrough' | ['NPM_TOKEN']
        'model' | 'claude-opus'
    }

    def "a malformed #key (#value) is a located error at #at naming the broken rule"() {
        when:
        def errors = ClaudeSettings.validate(FILE, WHERE, [(key): value])

        then: 'a list element is located at its index'
        errors*.file() == [FILE]
        errors*.where() == ["${WHERE}.${at}".toString()]
        errors[0].message().startsWith("settings break the Claude executor's schema rule 'type': ")

        where:
        key | value || at
        'allowedTools' | 'Read' || 'allowedTools'
        'allowedTools' | ['Read', 2] || 'allowedTools[1]'
        'disallowedTools' | ['Bash', 2] || 'disallowedTools[1]'
        'maxTurns' | 'five' || 'maxTurns'
        'roundTimeout' | true || 'roundTimeout'
        'roundTimeout' | ['PT1M'] || 'roundTimeout'
    }

    // D6: the schema admits a string, the one shape owner judges the duration grammar
    def "a roundTimeout string that names no duration (#value) is the shape owner's error, once"() {
        expect:
        ClaudeSettings.validate(FILE, WHERE, [roundTimeout: value]) == [
            new ConfigError(FILE, 'executor.settings.roundTimeout',
            "malformed 'roundTimeout': expected a positive number of seconds or a positive ISO-8601 duration string")
        ]

        where:
        value << [
            'not-a-duration',
            '',
            0,
            -5,
            'PT0S'
        ]
    }

    def "every roundTimeout shape accepted before the schema moved is accepted (#value)"() {
        expect:
        ClaudeSettings.validate(FILE, WHERE, [roundTimeout: value]) == []

        where:
        value << [30, 'PT30S', 'pt1m', '45s']
    }

    def "several problems are all reported, in key order"() {
        expect:
        ClaudeSettings.validate(FILE, 'verify[0].settings', [roundTimeout: 'nope', bogus: 1, maxTurns: 'x'])*.where() == [
            'verify[0].settings.bogus',
            'verify[0].settings.maxTurns',
            'verify[0].settings.roundTimeout'
        ]
    }

    def "the reported list is immutable"() {
        when:
        ClaudeSettings.validate(FILE, WHERE, [bogus: 1]).add(new ConfigError('x', 'y', 'z'))

        then:
        thrown(UnsupportedOperationException)
    }
}
