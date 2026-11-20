package com.github.oinsio.gnomish.adapter.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import spock.lang.Specification

/**
 * {@link VerifyCheckDtoDeserializer}: selects the DTO record of one {@code verify} entry — a
 * {@code type} token binds the record it names, an entry with no {@code type} and an {@code
 * executor} key binds the explicit form, and anything else fails as Jackson's own discriminator
 * error, so {@link StructuralParse} keeps its located messages.
 *
 * <p>Implements FR1, UX2 of migrate-verify-to-executor-contract; FR5 of load-pipeline-config.
 */
class VerifyCheckDtoDeserializerSpec extends Specification {

    private final ObjectMapper yaml = PipelineYaml.mapper()

    private VerifyCheckDto read(String entry) {
        yaml.readValue(entry, VerifyCheckDto)
    }

    // FR1, UX2: each sugar token binds the record it names, the token itself consumed
    def "type #token binds #record"() {
        expect:
        read(entry).getClass() == record

        where:
        token | entry || record
        'builtin' | 'type: builtin\nname: files_exist\n' || VerifyCheckDto.Builtin
        'command' | 'type: command\ncommand: make\n' || VerifyCheckDto.Command
        'external' | 'type: external\ncheckId: ci\n' || VerifyCheckDto.External
        'judge' | 'type: judge\ncriteriaFile: a.md\nmodel: m\nvotes: 1\n' || VerifyCheckDto.Judge
    }

    // FR1: with no type, an `executor` key selects the explicit form, every field bound raw
    def "an entry with no type and an executor key binds the explicit executor form"() {
        when:
        def dto = read('''\
            executor: sonarqube
            checkId: gate
            settings:
              project: acme
            interval: 10s
            timeout: 2m
            timeoutClass: infrastructure
            pinPaths: [sonar-project.properties]
            verifyIn: fresh-box
            threshold: warning
            expected: [sonar/gate]
            '''.stripIndent())

        then:
        dto == new VerifyCheckDto.Executor('sonarqube', 'gate', [project: 'acme'], '10s', '2m', 'infrastructure',
        ['sonar-project.properties'], 'fresh-box', 'warning', ['sonar/gate'])
    }

    // FR1: an explicit null type is no type, so the executor key still selects the explicit form
    def "a null type with an executor key binds the explicit executor form"() {
        expect:
        read('type: null\nexecutor: acme\n') instanceof VerifyCheckDto.Executor
    }

    // FR5 (load-pipeline-config): neither key is the missing-discriminator error
    def "an entry with neither a type nor an executor fails as a missing type id"() {
        when:
        read('checkId: ci\n')

        then:
        def e = thrown(InvalidTypeIdException)
        e.typeId == null
        e.baseType.rawClass == VerifyCheckDto
    }

    // FR5: an unknown token is the unknown-discriminator error naming the token
    def "an unknown type fails as an unknown type id naming it"() {
        when:
        read('type: lint\nexecutor: acme\n')

        then:
        def e = thrown(InvalidTypeIdException)
        e.typeId == 'lint'
        e.baseType.rawClass == VerifyCheckDto
    }

    // FR5: a token record does not declare `executor`, so mixing the two forms is an unknown field
    def "a typed entry that also declares an executor key fails on the unknown field"() {
        when:
        read('type: command\ncommand: make\nexecutor: shell\n')

        then:
        def e = thrown(UnrecognizedPropertyException)
        e.propertyName == 'executor'
    }

    // FR5: a verify entry must be a mapping
    def "a scalar verify entry is a type mismatch"() {
        when:
        read('just-a-string\n')

        then:
        thrown(MismatchedInputException)
    }
}
