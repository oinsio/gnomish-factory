package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.MechanismFieldErrors
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The executor-type wire vocabulary round trip (testing.md, "Every wire vocabulary has a
 * round-trip spec"; design D2 of add-command-executor). The tokens have no single owner: the
 * structural allowlist ({@code StructuralValidation}), the mapper ({@code StageDefinitionMapper}),
 * the blank-field wire check ({@code BlankMechanismFields}) and the domain's per-type messages
 * ({@code StageMechanismRule}) each spell them. No production {@code wire(e)} exists, so this spec
 * holds the expected token of every constant, and every feature iterates {@code ExecutorType.values()}:
 * a constant added with no row here, or mapped on one side only, fails a feature, not production.
 *
 * <p>FR1 of add-command-executor: the {@code command} token joins the vocabulary on every side.
 */
class ExecutorTypeWireRoundTripSpec extends Specification implements PipelineLoaderFixtureSupport {

    /** The one expected spelling per constant; every constant must have a row. */
    static final Map<ExecutorType, String> TOKENS = [
        (ExecutorType.API) : 'api',
        (ExecutorType.AGENT_CLI): 'agent-cli',
        (ExecutorType.PROGRAM) : 'program',
        (ExecutorType.COMMAND) : 'command',
    ]

    static final String MANIFEST = 'stages/gen/stage.yaml'

    @TempDir
    Path root

    static String token(ExecutorType type) {
        assert TOKENS.containsKey(type): "no wire token row for ${type}"
        TOKENS[type]
    }

    static ExecutorType mapped(String type) {
        StageDefinitionMapper.mapExecutor('stages/s/stage.yaml', new ExecutorDto(type, 'm', null), []).type()
    }

    static StageDto stage(String type) {
        new StageDto('purpose', [new ArtifactInputDto.Source()] as List<ArtifactInputDto>,
        [new ArtifactOutputDto('out')], new ExecutorDto(type, 'm', null),
        'stages/gen/instructions.md', [
            new VerifyCheckDto.Command('true')
        ], null, 'auto')
    }

    // FR1: the token table is total over the enum and injective
    def "every ExecutorType constant has exactly one distinct wire token"() {
        expect:
        TOKENS.keySet() == ExecutorType.values() as Set
        TOKENS.values().toSet().size() == ExecutorType.values().length
    }

    // FR1: wire(e) then the mapper gives e back, for every constant
    def "the token of #type maps back to #type"(ExecutorType type) {
        expect:
        mapped(token(type)) == type

        where:
        type << ExecutorType.values()
    }

    // FR1: the structural allowlist is exactly the tokens of all constants, in declaration order
    def "the structural allowlist accepts the token of #type"(ExecutorType type) {
        expect:
        StructuralValidation.checkStage(MANIFEST, stage(token(type))).isEmpty()

        where:
        type << ExecutorType.values()
    }

    // FR1: an unknown token is a located structural error listing every known token, and nothing more
    def "an unknown token (#unknown) is a located error naming exactly the known executors"() {
        expect:
        StructuralValidation.checkStage(MANIFEST, stage(unknown)) == [
            new ConfigError(MANIFEST, 'executor.type', "unknown executor '${unknown}'; known executors are ${ExecutorType.values().collect { token(it) }.join(', ')}" as String)
        ]

        where:
        unknown << [
            'shell',
            'API',
            'Command',
            'agent_cli',
            ''
        ]
    }

    // FR1: a missing token is a located structural error, never a defaulted type
    def "a missing token is a located structural error"() {
        expect:
        StructuralValidation.checkStage(MANIFEST, stage(null)) == [
            new ConfigError(MANIFEST, 'executor.type', "missing required field 'executor.type'")
        ]
    }

    // FR1: the mapper's documented fallback — only ever reached after the allowlist refused the token
    def "after the allowlist refused it, an unknown or missing token (#unknown) maps to API"() {
        expect:
        mapped(unknown) == ExecutorType.API

        where:
        unknown << [
            'shell',
            'API',
            'Command',
            '',
            null
        ]
    }

    // FR2: the blank-field wire check names every type but command as one that may not declare a command
    def "a blank command on a #type stage is a wire error iff the type is not command"(ExecutorType type) {
        given:
        List<ConfigError> errors = []

        when:
        BlankMechanismFields.check(errors, MANIFEST, new ExecutorDto(token(type), null, null, null, null, '  '))

        then:
        errors == (type == ExecutorType.COMMAND ? [] : [
            MechanismFieldErrors.commandOnStage(MANIFEST, token(type))
        ])

        where:
        type << ExecutorType.values()
    }

    // FR2: the blank-field wire check names command as the one type that may not declare a model
    def "a blank model on a #type stage is a wire error iff the type is command"(ExecutorType type) {
        given:
        List<ConfigError> errors = []

        when:
        BlankMechanismFields.check(errors, MANIFEST, new ExecutorDto(token(type), '  ', null, null, null, 'make gen'))

        then:
        errors == (type == ExecutorType.COMMAND ? [
            MechanismFieldErrors.modelOnCommandStage(MANIFEST)
        ] : [])

        where:
        type << ExecutorType.values()
    }

    // FR2, UX2: the domain rule names a non-command type by its wire token in the forbidden-command error
    def "a command declared on a #type stage is rejected by the domain naming the type's token"() {
        given:
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n'
                + 'executors:\n  acme:\n    type: program\n    program: [acme-executor]\n')
        write('pipeline.yaml', 'stages:\n  - gen\n')
        write('stages/gen/instructions.md', 'generate\n')
        write(MANIFEST, "purpose: generate sources\nexecutor:\n  type: ${token(type)}\n  model: m\n"
                + "  name: acme\n  command: make gen\ninstructions: stages/gen/instructions.md\nadvancement: auto\n")

        when:
        def outcome = loadTree()
        def errors = outcome instanceof LoadOutcome.Invalid ? (outcome as LoadOutcome.Invalid).errors() : []

        then:
        errors.findAll {
            it.where() == 'executor.command'
        } == [
            MechanismFieldErrors.commandOnStage(MANIFEST, token(type))
        ]

        where:
        type << ExecutorType.values().findAll { it != ExecutorType.COMMAND }
    }
}
