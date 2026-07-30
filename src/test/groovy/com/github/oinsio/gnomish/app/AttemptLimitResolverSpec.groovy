package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import spock.lang.Specification

/**
 * AttemptLimitResolver#resolve (D10): the starting stage's own
 * {@code autonomy.attemptLimit} when the position names a known stage, else
 * the pipeline's default limits — the latter fallback also covers a position
 * that is not {@code Position.AtStage} at all (e.g. {@code PipelineEnd}) and a
 * stage name absent from the pipeline (the engine's own {@code
 * PipelineMismatch} check normally prevents this, per the method's own
 * javadoc — exercised here directly as a defensive fallback).
 *
 * <p>{@code resolve} is only reached through {@link ManualRunAssembly#assemble},
 * which never surfaces the resolved value on its {@code Run} result and is only
 * ever called with a freshly synthesized task's {@code Position.AtStage} (see
 * {@link AdHocTaskSynthesizer}, which validates {@code --from-stage} against
 * known stages and never produces a {@code PipelineEnd}). The two fallback
 * branches are otherwise unreachable through any public entrypoint, so they are
 * exercised here directly rather than left undertested.
 *
 * <p>Implements D10 of add-manual-run.
 */
class AttemptLimitResolverSpec extends Specification {

    private static StageDefinition stage(String name, int attemptLimit) {
        new StageDefinition(
                name,
                'purpose',
                [],
                [],
                new StageDefinition.Executor(ExecutorType.API, 'model-x', [:]),
                'instructions.md',
                [],
                new AutonomyLimits(attemptLimit),
                AdvancementMode.AUTO)
    }

    def "resolves the named stage's own attemptLimit when the position names a known stage"() {
        given:
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [stage('build', 7)])

        expect:
        AttemptLimitResolver.resolve(definition, new Position.AtStage('build')) == 7
    }

    def "falls back to the pipeline default when the position is not AtStage"() {
        given:
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [stage('build', 7)])

        expect:
        AttemptLimitResolver.resolve(definition, new Position.PipelineEnd()) == 3
    }

    def "falls back to the pipeline default when the position names a stage absent from the pipeline"() {
        given:
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [stage('build', 7)])

        expect:
        AttemptLimitResolver.resolve(definition, new Position.AtStage('deploy')) == 3
    }
}
