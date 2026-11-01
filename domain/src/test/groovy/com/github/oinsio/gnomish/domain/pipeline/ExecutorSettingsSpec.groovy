package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * {@link ExecutorSettings}, the one merge of the settings a {@code program} executor receives (FR9 of
 * define-executor-contract, task 7.1): the declaration's, the stage's over them key by key, the model
 * last under {@code model}; each key remembering the law file that set it.
 */
class ExecutorSettingsSpec extends Specification {

    static final ExecutorDeclaration DECLARATION = new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM,
    ['acme'], [maxTurns: 3, style: 'strict', model: 'declared-model'], [], [])

    private static StageDefinition.Executor stage(String model, Map<String, Object> settings) {
        new StageDefinition.Executor(ExecutorType.PROGRAM, model, settings, Sandbox.none(), 'acme')
    }

    // FR9: the stage's values replace the declaration's, and a present model is carried as the setting `model`
    def "FR9: the stage's settings override the declaration's and the stage model comes last"() {
        when:
        def merged = ExecutorSettings.of(DECLARATION, stage('acme-model-1', [maxTurns: 9, extra: [a: 1]]))

        then:
        merged.values() == [maxTurns: 9, style: 'strict', model: 'acme-model-1', extra: [a: 1]]
        merged.originOf('maxTurns') == ExecutorSettings.Origin.STAGE
        merged.originOf('style') == ExecutorSettings.Origin.DECLARATION
        merged.originOf('model') == ExecutorSettings.Origin.MODEL
        merged.originOf('extra.a') == ExecutorSettings.Origin.STAGE
        merged.originOf('extra[0]') == ExecutorSettings.Origin.STAGE
    }

    // FR9: an absent model sends no model key of the stage; the declaration's stays
    def "FR9: a blank stage model leaves the settings without a stage model"() {
        when:
        def merged = ExecutorSettings.of(DECLARATION, stage('', [:]))

        then:
        merged.values() == DECLARATION.settings()
        merged.originOf('model') == ExecutorSettings.Origin.DECLARATION
    }

    // FR9: a declaration no stage names is its own settings; a key nobody wrote is the declaration's to supply
    def "FR9: a declaration alone is its settings, and an unwritten key is located at the declaration"() {
        when:
        def alone = ExecutorSettings.of(DECLARATION)

        then:
        alone.values() == DECLARATION.settings()
        alone.originOf('missing.deep') == ExecutorSettings.Origin.DECLARATION
        alone == ExecutorSettings.of(DECLARATION)
        alone.hashCode() == ExecutorSettings.of(DECLARATION).hashCode()
        alone != ExecutorSettings.of(DECLARATION, stage('m', [:]))
        alone.hashCode() != ExecutorSettings.of(DECLARATION, stage('m', [:])).hashCode()
        alone != 'not settings'
        alone.toString().startsWith('ExecutorSettings{') && alone.toString().contains('maxTurns=3')
    }

    // FR9: the merged values are immutable
    def "FR9: the merged values cannot be changed by a reader"() {
        when:
        ExecutorSettings.of(DECLARATION).values().put('x', 1)

        then:
        thrown(UnsupportedOperationException)
    }
}
