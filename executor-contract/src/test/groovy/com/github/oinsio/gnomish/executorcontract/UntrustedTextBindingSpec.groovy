package com.github.oinsio.gnomish.executorcontract

import com.fasterxml.jackson.databind.JsonMappingException
import spock.lang.Specification

/**
 * A carrier field binds only inside the result-file reader (FR6, NFR-S2 of
 * define-executor-contract): read through the contract's binding directly, there is no family to
 * mint it in, so the read fails instead of guessing one.
 */
class UntrustedTextBindingSpec extends Specification {

    def "NFR-S2: a carrier field read outside ResultFileReader fails the read"() {
        when:
        ExecutorContractJson.reader().forType(type).readValue(json)

        then:
        def e = thrown(JsonMappingException)
        e.originalMessage == 'an untrusted-text field is read only through ResultFileReader, which names its family'

        where:
        type | json
        QuestionWire | '{"question":"q"}'
        Answer | '{"kind":"select","chosen":"main"}'
        ExecutorResult | '{"status":"completed","reason":"done"}'
    }

    def "NFR-S2: a carrier written is the JSON string it was read from"() {
        expect:
        WireJson.writeValueAsString(new Denial(WireJson.t('blocked \u001B[2J'), null, WireJson.t('d'))) ==
                '{"message":"blocked \\u001B[2J","details":"d"}'
    }
}
