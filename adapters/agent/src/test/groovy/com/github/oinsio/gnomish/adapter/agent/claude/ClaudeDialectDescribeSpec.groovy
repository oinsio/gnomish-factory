package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.WireDocument
import com.github.oinsio.gnomish.executorcontract.WireSchemas
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR3, FR7, NFR-S1 of extract-agent-round-runner (task 2.3): what the Claude dialect's describe
 * declares — its roles, capabilities and enforcement level; its credentials, egress host and
 * configuration isolation; and its settings schema — for a run whose allowlist resolves the base URL
 * or does not.
 */
class ClaudeDialectDescribeSpec extends Specification {

    def "FR3: describe states the three roles, the capabilities, the tool gate and the version"() {
        when:
        def describe = over([:])

        then:
        describe.protocolVersion() == Describe.SPOKEN_PROTOCOL_VERSION
        describe.roles() == [
            Role.TRANSFORM,
            Role.VERDICT,
            Role.DECISION
        ]
        describe.capabilities() == [
            Capability.STREAMS_PROGRESS,
            Capability.CANCEL
        ]
        !describe.capabilities().contains(Capability.POLL)
        describe.enforcementLevel() == EnforcementLevel.TOOL_GATE
        describe.reportsUsage()
        !describe.checkIdIsPinnedPath()
        describe.version().forLog() == '2.1.0'
    }

    // Scenario: executor/claude-dialect / Describe names the credentials, the egress host and the config isolation
    def "FR7, NFR-S1: describe names the credentials, the base URL's host and the config isolation"() {
        when:
        def describe = over([ANTHROPIC_BASE_URL: 'https://gateway.example.com/anthropic'])

        then:
        describe.credentials() as Set == [
            'ANTHROPIC_API_KEY',
            'CLAUDE_CODE_OAUTH_TOKEN',
            'ANTHROPIC_AUTH_TOKEN',
            'ANTHROPIC_BASE_URL',
            'ANTHROPIC_MODEL'
        ] as Set
        describe.credentials().size() == 5
        describe.egress() == ['gateway.example.com']
        describe.configDirVariable() == 'CLAUDE_CONFIG_DIR'
        describe.policyDisablingFlags() == [
            '--setting-sources=user',
            '--strict-mcp-config'
        ]
    }

    def "FR7: with no base URL configured the egress host is the provider's own"() {
        expect:
        over([:]).egress() == ['api.anthropic.com']
    }

    def "FR7: the settings schema is the dialect's, and its wire form satisfies the contract's describe schema"() {
        given:
        def describe = over([:])

        when: 'the describe is written in the contract\'s wire form'
        def written = ExecutorContractJson.writer().writeValueAsString(describe)

        then: 'the schema is the dialect\'s, holding exactly its keys'
        describe.settingsSchema() == ClaudeSettings.schema()
        describe.settingsSchema().get('properties').fieldNames().toList() as Set ==
                [
                    'allowedTools',
                    'disallowedTools',
                    'maxTurns',
                    'roundTimeout'
                ] as Set
        !describe.settingsSchema().get('additionalProperties').asBoolean()

        and: 'the written describe is one the contract\'s describe schema accepts'
        WireSchemas.judge(WireDocument.DESCRIBE, written).isEmpty()
    }

    private static Describe over(Map<String, String> values) {
        ClaudeDialect.of('claude', UntrustedText.subprocess('2.1.0'),
                ChildEnvAllowlist.of([], [], TestChildEnvironment.valuesOf(values))).describe()
    }
}
