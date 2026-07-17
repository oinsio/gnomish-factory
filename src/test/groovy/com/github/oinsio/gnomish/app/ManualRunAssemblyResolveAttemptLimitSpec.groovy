package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.lang.reflect.Method
import spock.lang.Specification

/**
 * ManualRunAssembly#resolveAttemptLimit (D10): the starting stage's own
 * {@code autonomy.attemptLimit} when the position names a known stage, else
 * the pipeline's default limits — the latter fallback also covers a position
 * that is not {@code Position.AtStage} at all (e.g. {@code PipelineEnd}) and a
 * stage name absent from the pipeline (the engine's own {@code
 * PipelineMismatch} check normally prevents this, per the method's own
 * javadoc — exercised here directly as a defensive fallback).
 *
 * <p>{@code resolveAttemptLimit} is private and only reachable through {@link
 * ManualRunAssembly#assemble}, which never surfaces the resolved value on its
 * {@code Run} result and is only ever called with a freshly synthesized
 * task's {@code Position.AtStage} (see {@link AdHocTaskSynthesizer}, which
 * validates {@code --from-stage} against known stages and never produces a
 * {@code PipelineEnd}). Reflection is used here specifically to reach the two
 * fallback branches that are otherwise unreachable through any public
 * entrypoint, rather than leaving them undertested.
 *
 * <p>Implements D10 of add-manual-run.
 */
class ManualRunAssemblyResolveAttemptLimitSpec extends Specification {

    private static final Method RESOLVE = ManualRunAssembly.getDeclaredMethod(
    'resolveAttemptLimit', PipelineDefinition, Position)

    static {
        RESOLVE.setAccessible(true)
    }

    private static int resolveAttemptLimit(PipelineDefinition definition, Position position) {
        (int) RESOLVE.invoke(null, definition, position)
    }

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
        resolveAttemptLimit(definition, new Position.AtStage('build')) == 7
    }

    def "falls back to the pipeline default when the position is not AtStage"() {
        given:
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [stage('build', 7)])

        expect:
        resolveAttemptLimit(definition, new Position.PipelineEnd()) == 3
    }

    def "falls back to the pipeline default when the position names a stage absent from the pipeline"() {
        given:
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [stage('build', 7)])

        expect:
        resolveAttemptLimit(definition, new Position.AtStage('deploy')) == 3
    }
}
