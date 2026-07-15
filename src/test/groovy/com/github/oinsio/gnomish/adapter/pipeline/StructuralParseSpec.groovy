package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import spock.lang.Specification

/**
 * StructuralParse turns a single .gnomish/ YAML file's text into a DTO, catching
 * every Jackson content problem and converting it into a single located
 * ConfigError for that file (design D2/D3/D6): malformed YAML, a type mismatch,
 * an unrecognized property, and an unknown/missing type/kind discriminator all
 * become data — never an escaping exception. A file that will not parse at all
 * short-circuits its own downstream checks, so parse returns Failed and the
 * caller skips shape validation for that file (D6). Genuine I/O faults are not
 * this class's concern — it parses already-read text.
 * Implements FR5 of load-pipeline-config.
 */
class StructuralParseSpec extends Specification {

    def "well-formed text parses into the DTO"() {
        given: 'a valid config.yaml body'
        def body = 'schemaVersion: "1"\n'

        when: 'it is parsed'
        def result = StructuralParse.parse('config.yaml', body, ConfigDto)

        then: 'an Ok carries the deserialized DTO'
        result instanceof StructuralParse.Ok
        (result as StructuralParse.Ok<ConfigDto>).value().schemaVersion() == '1'
    }

    def "a content problem yields a single located parse error and no exception escapes"() {
        when: 'a structurally broken file is parsed'
        def result = StructuralParse.parse(file, body, type)

        then: 'exactly one located ConfigError is produced for that file'
        result instanceof StructuralParse.Failed
        def errors = (result as StructuralParse.Failed).errors()
        errors.size() == 1
        with(errors[0] as ConfigError) {
            it.file() == file
            it.where() == where
            it.message() == message
        }

        where:
        scenario                | file                          | body                                       | type          || where                       | message
        'malformed YAML'        | 'config.yaml'                 | 'foo: [unclosed\n'                         | ConfigDto     || 'config.yaml'               | 'malformed YAML: the file is not well-formed and cannot be parsed'
        'type mismatch'         | 'pipeline.yaml'               | 'stages: notalist\n'                       | PipelineDto   || 'stages'                    | "type mismatch: 'stages' has the wrong YAML type"
        'unknown property'      | 'config.yaml'                 | 'schemaVersion: "1"\nbogus: x\n'           | ConfigDto     || 'bogus'                     | "unknown field 'bogus'"
        'unknown verify type'   | 'stages/build/stage.yaml'     | 'verify:\n  - type: foo\n'                 | StageDto      || 'verify[0]'                 | "unknown verify check type 'foo'; known types are builtin, command, external, judge"
        'unknown input kind'    | 'stages/build/stage.yaml'     | 'inputs:\n  - kind: bar\n'                 | StageDto      || 'inputs[0]'                 | "unknown input kind 'bar'; known kinds are internal, source"
        'missing verify type'   | 'stages/build/stage.yaml'     | 'verify:\n  - name: x\n'                   | StageDto      || 'verify[0]'                 | "missing required verify check 'type'; known types are builtin, command, external, judge"
    }
}
