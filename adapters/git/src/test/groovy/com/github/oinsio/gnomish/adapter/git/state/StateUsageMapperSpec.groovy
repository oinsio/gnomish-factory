package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import java.time.Duration
import spock.lang.Specification

/**
 * The {@code state.json} usage mapper's own round trip, in memory: per-tool aggregates and
 * per-model token counts survive the mapping to the DTOs and back, element for element.
 *
 * <p>The spec is kept deliberately tiny: it is meant to be the first covering test PIT tries for
 * the mapper's mutants, and PIT orders covering tests by their measured time. Fixtures are
 * therefore built once, and {@code setupSpec} (whose time is not counted) runs the mapping once so
 * class loading lands outside every feature.
 *
 * <p>FR1 of kill-expensive-mutants (design D2).
 */
class StateUsageMapperSpec extends Specification {

    private static final ExecutorUsage TWO_TOOLS_TWO_MODELS = new ExecutorUsage(
    Duration.ofMillis(1500),
    [
        new ToolUsage('Bash', 3, Duration.ofMillis(700)),
        new ToolUsage('Read', 1, Duration.ofMillis(20))
    ],
    ['model-a': new TokenUsage(10, 20, 30, 40), 'model-b': new TokenUsage(1, 2, 3, 4)])

    private static final List<StateByToolDto> TWO_TOOL_DTOS = [
        new StateByToolDto('Bash', 3, 700),
        new StateByToolDto('Read', 1, 20)
    ]

    private static final Map<String, StateTokenUsageDto> TWO_MODEL_DTOS = [
        'model-a': new StateTokenUsageDto(10, 20, 30, 40),
        'model-b': new StateTokenUsageDto(1, 2, 3, 4)
    ]

    private static final ExecutorUsage NO_TOOLS = new ExecutorUsage(
    Duration.ZERO, [], ['model-a': new TokenUsage(1, 2, 3, 4)])

    private static final ExecutorUsage NO_MODELS = new ExecutorUsage(
    Duration.ZERO, [
        new ToolUsage('Bash', 1, Duration.ofMillis(5))
    ], [:])

    def setupSpec() {
        StateUsageMapper.fromUsage(StateUsageMapper.toUsage(TWO_TOOLS_TWO_MODELS))
    }

    def "FR1: a two-tool, two-model usage maps to the DTOs element for element and back to itself"() {
        when:
        def dto = StateUsageMapper.toUsage(TWO_TOOLS_TWO_MODELS)

        then:
        dto.byTool().size() == 2
        dto.byTool() == TWO_TOOL_DTOS
        dto.tokensByModel().size() == 2
        dto.tokensByModel() == TWO_MODEL_DTOS
        StateUsageMapper.fromUsage(dto) == TWO_TOOLS_TWO_MODELS
    }

    def "FR1: a usage with no tools maps to an empty tool list, never a fabricated entry"() {
        when:
        def dto = StateUsageMapper.toUsage(NO_TOOLS)

        then:
        dto.byTool().isEmpty()
        dto.tokensByModel().size() == 1
    }

    def "FR1: a usage with no models maps to an empty model map, never a fabricated entry"() {
        when:
        def dto = StateUsageMapper.toUsage(NO_MODELS)

        then:
        dto.tokensByModel().isEmpty()
        dto.byTool().size() == 1
    }
}
