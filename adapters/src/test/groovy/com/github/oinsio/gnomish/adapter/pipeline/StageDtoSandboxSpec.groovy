package com.github.oinsio.gnomish.adapter.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * StageDto round-trip for the sandbox capability: the Mechanism {@code sandbox} block, a
 * command's {@code verifyIn}, and an external check's {@code pinPaths} bind from their
 * camelCase wire keys, and a repo-declared {@code binding} reaches the DTO instead of
 * being silently dropped. Split from {@link StageDtoSpec}, which owns the eight
 * stage-contract sections and the four verify-check variants.
 * Implements FR12, FR13, FR14, FR16 of add-sandbox-core.
 */
class StageDtoSandboxSpec extends Specification {

    private final ObjectMapper yaml = PipelineYaml.mapper()

    // FR12/FR13/FR16 (add-sandbox-core): the Mechanism sandbox block, a
    // command's verifyIn, and an external check's pinPaths bind from their
    // camelCase wire keys (FAIL_ON_UNKNOWN is on, so a wrong key would throw)
    def "sandbox declarations, verifyIn, and pinPaths deserialize from their wire keys"() {
        given: 'a stage declaring a sandbox, a fresh-box command, and a pinned external'
        def body = '''\
            purpose: "Build it"
            inputs:
              - kind: source
            outputs:
              - id: impl-diff
            executor:
              type: agent-cli
              model: build-model
              sandbox:
                needs:
                  - docker-inside
                requiresFresh: true
            instructions: stages/build/instructions.md
            verify:
              - type: command
                command: "./gradlew test"
                verifyIn: fresh-box
              - type: external
                checkId: .github/workflows/ci.yml
                interval: 30s
                timeout: 5m
                pinPaths:
                  - .github/workflows/ci.yml
                  - analyzer/config.xml
            advancement: auto
            '''.stripIndent()

        when: 'the stage is read'
        def dto = yaml.readValue(body, StageDto)

        then: 'the sandbox block binds needs and requiresFresh, binding absent'
        def sandbox = dto.executor().sandbox()
        sandbox.needs() == ['docker-inside']
        sandbox.requiresFresh()
        sandbox.binding() == null

        and: 'the command carries its raw verifyIn string'
        (dto.verify()[0] as VerifyCheckDto.Command).verifyIn() == 'fresh-box'

        and: 'the external check carries its pin paths in declaration order'
        (dto.verify()[1] as VerifyCheckDto.External).pinPaths() == [
            '.github/workflows/ci.yml',
            'analyzer/config.xml'
        ]
    }

    // FR14 (add-sandbox-core): a repo-declared binding binds to the DTO field so
    // StructuralValidation can reject it (it is never silently dropped)
    def "a sandbox binding deserializes into the DTO for the tighten-only check to reject"() {
        given: 'a stage whose sandbox names a binding'
        def body = '''\
            purpose: "Build it"
            inputs:
              - kind: source
            outputs:
              - id: impl-diff
            executor:
              type: agent-cli
              model: build-model
              sandbox:
                binding: host
            instructions: stages/build/instructions.md
            verify:
              - type: command
                command: "true"
            advancement: auto
            '''.stripIndent()

        when: 'the stage is read'
        def dto = yaml.readValue(body, StageDto)

        then: 'the binding is carried on the DTO for FR14 rejection'
        dto.executor().sandbox().binding() == 'host'
        dto.executor().sandbox().needs() == null
    }
}
