package com.github.oinsio.gnomish.executorcontract

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import spock.lang.Specification

/**
 * FR16 of migrate-verify-to-executor-contract (design D17): the describe's static pin flag,
 * {@code checkIdIsPinnedPath}, on the wire — declared, it is read and written as {@code true} and
 * its schema accepts it; omitted, it reads as {@code false} and a describe written before the field
 * existed validates, reads and writes back unchanged. The pin set the flag feeds is the pin
 * decorator's (task 4.2f, `PinDecoratorSpec`); these features pin the wire half of the two
 * scenarios the decorator's spec completes.
 */
class DescribePinFlagSpec extends Specification {

    private static final String OLDER = '''{"protocolVersion":1,"version":"1","roles":["verdict"],"capabilities":[],
        "settingsSchema":{},"credentials":[],"egress":[],"policyDisablingFlags":[],"enforcementLevel":"PROMPT_ONLY",
        "reportsUsage":false}'''

    // Scenario: executor/wire-contract / Declared flag pins the check id
    def "FR16: a describe declaring checkIdIsPinnedPath validates, reads as true and writes the flag back"() {
        given:
        def declared = OLDER.replace('"reportsUsage":false', '"reportsUsage":false,"checkIdIsPinnedPath":true')

        when:
        def describe = WireJson.readValue(declared, Describe)

        then:
        WireSchemas.judge(WireDocument.DESCRIBE, declared).isEmpty()
        describe.checkIdIsPinnedPath()
        WireJson.readTree(WireJson.writeValueAsString(describe)) == WireJson.readTree(declared)
    }

    // Scenario: executor/wire-contract / Absent flag pins nothing of its own
    def "FR16: an older describe without the field validates unchanged, reads as false and writes back as itself"() {
        when:
        def describe = WireJson.readValue(OLDER, Describe)

        then:
        WireSchemas.judge(WireDocument.DESCRIBE, OLDER).isEmpty()
        !describe.checkIdIsPinnedPath()
        WireJson.readTree(WireJson.writeValueAsString(describe)) == WireJson.readTree(OLDER)
    }

    def "FR16: a declared false is the absent flag, and the describe writes no field for it"() {
        given:
        def declared = OLDER.replace('"reportsUsage":false', '"reportsUsage":false,"checkIdIsPinnedPath":false')

        when:
        def describe = WireJson.readValue(declared, Describe)

        then:
        !describe.checkIdIsPinnedPath()
        !WireJson.valueToTree(describe).has('checkIdIsPinnedPath')
    }

    def "FR16: the describe schema refuses a #why flag, which the conformance kit's describe case reports"() {
        expect:
        !WireSchemas.judge(WireDocument.DESCRIBE,
                OLDER.replace('"reportsUsage":false', '"reportsUsage":false,"checkIdIsPinnedPath":' + value)).isEmpty()

        where:
        why | value
        'string' | '"true"'
        'numeric' | '1'
        'null' | 'null'
    }

    def "FR16: a #why flag fails the read, never read as either answer"() {
        when:
        WireJson.readValue(OLDER.replace('"reportsUsage":false', '"reportsUsage":false,"checkIdIsPinnedPath":' + value),
                Describe)

        then:
        def refusal = thrown(MismatchedInputException)
        refusal.message.contains("a contract flag is a JSON boolean, got ${token}")

        where:
        why | value || token
        'null' | 'null' || 'null'
        'string' | '"true"' || 'VALUE_STRING'
        'numeric' | '1' || 'VALUE_NUMBER_INT'
    }
}
