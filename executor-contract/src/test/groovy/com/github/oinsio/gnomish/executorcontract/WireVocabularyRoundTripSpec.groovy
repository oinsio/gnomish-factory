package com.github.oinsio.gnomish.executorcontract

import static com.github.oinsio.gnomish.executorcontract.WireJson.t

import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR2, NFR-R3 of define-executor-contract: every wire vocabulary of the contract round-trips over its
 * full constant set — each wire enum through {@code fromWire(wire(e))} and through Jackson, the
 * policy kinds through their type names — and each vocabulary's unknown-token arm is pinned
 * (testing.md, "Every wire vocabulary has a round-trip spec").
 */
class WireVocabularyRoundTripSpec extends Specification {

    private static final List<Class<? extends Enum>> WIRE_ENUMS = [
        Role,
        Capability,
        Status,
        FailureClass,
        UsageProvenance,
        EnforcementLevel,
        AnswerKind,
        SarifLevel
    ]

    private static List<Enum> everyConstant() {
        WIRE_ENUMS.collectMany { it.enumConstants as List }
    }

    def "NFR-R3: the vocabulary list names every wire enum of the module"() {
        given:
        def packageDir = Path.of(WireToken.getResource('WireToken.class').toURI()).parent
        def found = Files.list(packageDir).withCloseable { files ->
            files.map { it.fileName.toString() }
            .filter { it.endsWith('.class') && !it.contains('$') }
            .map {
                Class.forName("${WireToken.package.name}.${it - '.class'}")
            }
            .filter { it.isEnum() && WireToken.isAssignableFrom(it) }
            .toList()
        }

        expect:
        found as Set == WIRE_ENUMS as Set
    }

    // Scenario: executor/wire-contract / Status tokens round-trip
    def "FR2: #constant.declaringClass.simpleName #constant reads back as itself from its token '#constant.wire()'"() {
        expect:
        constant.declaringClass.fromWire(constant.wire()) == Optional.of(constant)
        WireJson.writeValueAsString(constant) == "\"${constant.wire()}\""
        WireJson.readValue(WireJson.writeValueAsString(constant), constant.declaringClass) == constant

        where:
        constant << everyConstant()
    }

    def "NFR-R3: no two constants of #type.simpleName share a token, and a token outside the set reads as none"() {
        given:
        def tokens = type.enumConstants*.wire()

        expect:
        tokens.toSet().size() == tokens.size()
        type.fromWire('') == Optional.empty()
        type.fromWire('MADE_UP') == Optional.empty()

        where:
        type << WIRE_ENUMS
    }

    def "FR2: an unknown #vocabulary token reads by its arm, as #expected"() {
        expect:
        read() == expected

        where:
        vocabulary | read || expected
        'status' | {
            readResult('{"status":"done"}')
        } || [
            Status.FAILED,
            FailureClass.INFRASTRUCTURE,
            "unknown status 'done'"
        ]
        'failure class' | {
            readResult('{"status":"failed","failureClass":"oops"}')
        } || [
            Status.FAILED,
            FailureClass.INFRASTRUCTURE,
            "unknown failureClass 'oops'"
        ]
        'usage provenance' | {
            WireJson.readValue('"GUESSED"', UsageProvenance)
        } || UsageProvenance.ABSENT
        'enforcement level' | {
            WireJson.readValue('"QUANTUM"', EnforcementLevel)
        } || EnforcementLevel.PROMPT_ONLY
        'answer kind' | {
            WireJson.answer(WireJson.readTree('{"kind":"poll"}'))
        } || new AnswerReading.Malformed(t("unknown answer kind 'poll'"))
        'sarif level' | {
            WireJson.answer(WireJson.readTree('{"kind":"verdict","sarif":{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"j"}},' +
            '"results":[{"level":"fatal","message":{"text":"m"}}]}]}}'))
        } || new AnswerReading.Malformed(t("unknown SARIF level 'fatal'"))
        'role' | { WireJson.readValue('"publish"', Role) } || null
        'capability' | { WireJson.readValue('"teleport"', Capability) } || null
    }

    private static List<Object> readResult(String json) {
        def result = WireJson.readValue(json, ExecutorResult)
        [
            result.status(),
            result.failureClass(),
            result.reason()?.raw()
        ]
    }

    def "FR11: the policy kind #kind.simpleName writes its type name and reads back as itself"() {
        given:
        def policy = kind == Policy.WriteExactly ? new Policy.WriteExactly('out.md') : kind.getConstructor().newInstance()
        def json = WireJson.writeValueAsString(policy)

        expect:
        WireJson.readTree(json).path('kind').asText() == kind.getAnnotation(JsonTypeName).value()
        WireJson.readValue(json, Policy) == policy

        where:
        kind << (Policy.permittedSubclasses as List)
    }

    def "FR11: an unknown policy kind is refused, never widened"() {
        when:
        WireJson.readValue('{"kind":"everything"}', Policy)

        then:
        thrown(InvalidTypeIdException)
    }
}
