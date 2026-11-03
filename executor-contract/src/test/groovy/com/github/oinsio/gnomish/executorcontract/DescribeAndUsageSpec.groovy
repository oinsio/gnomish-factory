package com.github.oinsio.gnomish.executorcontract

import static com.github.oinsio.gnomish.executorcontract.WireJson.t

import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import spock.lang.Specification

/**
 * FR4, FR13, NFR-O1 of define-executor-contract: a describe drops the roles and capabilities it
 * cannot name, reads an unknown enforcement level as the weakest, and refuses the values its schema refuses.
 */
class DescribeAndUsageSpec extends Specification {

    private static final String DESCRIBE = '''{"protocolVersion":1,"version":"1","roles":["transform","publish","verdict"],
        "capabilities":["teleport","poll"],"settingsSchema":{},"credentials":[],"egress":[],"policyDisablingFlags":[],
        "enforcementLevel":"QUANTUM","reportsUsage":true}'''

    def "FR4: unknown roles and capabilities are dropped and an unknown enforcement level reads as PROMPT_ONLY"() {
        when:
        def describe = WireJson.readValue(DESCRIBE, Describe)

        then:
        describe.roles() == [Role.TRANSFORM, Role.VERDICT]
        describe.capabilities() == [Capability.POLL]
        describe.enforcementLevel() == EnforcementLevel.PROMPT_ONLY
        describe.reportsUsage()
    }

    def "FR4: a describe refuses #why"() {
        when:
        WireJson.readValue(DESCRIBE.replace(from, to), Describe)

        then:
        def refusal = thrown(ValueInstantiationException)
        refusal.message.contains(message)

        where:
        why | from | to || message
        'protocol version 0' | '"protocolVersion":1' | '"protocolVersion":0' || 'protocolVersion must be at least 1, was 0'
        'a blank version' | '"version":"1"' | '"version":" "' || 'version must not be blank'
        'a lower-case credential' | '"credentials":[]' | '"credentials":["api_key"]' || 'credentials[] is not an environment variable name: api_key'
        'a blank egress host' | '"egress":[]' | '"egress":[""]' || 'egress[] must not be blank'
        'a bad config variable'| '"egress":[]' | '"egress":[],"configDirVariable":"1X"' || 'configDirVariable is not an environment variable name: 1X'
    }

    def "FR4: a describe copies its lists"() {
        given:
        def roles = [Role.TRANSFORM]
        def capabilities = [Capability.CANCEL]
        def credentials = ['K']
        def egress = ['h']
        def flags = ['--f']
        def describe = new Describe(1, t('1'), roles, capabilities, WireSamples.object('{}'), credentials, egress,
                'C_DIR', flags, EnforcementLevel.OS_SANDBOX, false)

        when:
        [
            roles,
            capabilities,
            credentials,
            egress,
            flags
        ]*.clear()

        then:
        describe.roles() == [Role.TRANSFORM]
        describe.capabilities() == [Capability.CANCEL]
        describe.credentials() == ['K']
        describe.egress() == ['h']
        describe.policyDisablingFlags() == ['--f']
        describe.configDirVariable() == 'C_DIR'
    }

    def "NFR-O1: usage accepts zero"() {
        expect:
        new ModelUsage(0, 0, 0, 0, BigDecimal.ZERO).costUsd() == BigDecimal.ZERO
        new ToolUsage('t', 0, 0L).totalMillis() == 0L
        new Usage(UsageProvenance.REPORTED, 0L, [:], []).wallMillis() == 0L
    }

    def "NFR-O1: usage refuses a negative #field"() {
        when:
        build()

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == "${field} must ${field == 'costUsd' ? 'not be negative, was -1' : 'be at least 0, was -1'}"

        where:
        field | build
        'input' | { new ModelUsage(-1, 0, 0, 0, null) }
        'output' | { new ModelUsage(0, -1, 0, 0, null) }
        'cacheCreation' | { new ModelUsage(0, 0, -1, 0, null) }
        'cacheRead' | { new ModelUsage(0, 0, 0, -1, null) }
        'costUsd' | { new ModelUsage(0, 0, 0, 0, -1G) }
        'tools[].calls' | { new ToolUsage('t', -1, null) }
        'tools[].totalMillis'| { new ToolUsage('t', 0, -1L) }
        'usage.wallMillis' | {
            new Usage(UsageProvenance.REPORTED, -1L, [:], [])
        }
    }

    def "NFR-O1: a tool has a name, and usage copies its map and list"() {
        when:
        new ToolUsage(' ', 0, null)

        then:
        thrown(IllegalArgumentException)

        when:
        def models = ['m': new ModelUsage(1, 1, 1, 1, null)]
        def tools = [new ToolUsage('t', 1, null)]
        def usage = new Usage(UsageProvenance.REPORTED, null, models, tools)
        models.clear()
        tools.clear()

        then:
        usage.models().size() == 1
        usage.tools().size() == 1
    }
}
