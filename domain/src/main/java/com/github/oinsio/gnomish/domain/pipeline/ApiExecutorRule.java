package com.github.oinsio.gnomish.domain.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure fail-fast rule (design D6, NG3 of add-agent-executor): every stage
 * whose Mechanism section declares {@code executor.type: api} is rejected at
 * startup, before any dialog (UX2). This change wires only the
 * {@code agent-cli} mechanism (FR1–FR9); no runtime dispatch or composite
 * executor exists yet for {@code api}, so accepting such a manifest here
 * would silently produce a pipeline that can never actually run its stage —
 * catching it during pipeline loading, alongside the other domain rules, is
 * cheaper and clearer than failing later at dispatch time.
 *
 * <p>Problems are located {@link ConfigError}s naming the stage manifest
 * (NFR-O1, UX2) and the {@code executor.type} field, with a message that
 * names {@code agent-cli} as the only currently supported executor type — an
 * actionable instruction for the operator, since this text is what surfaces
 * on stderr before exit code 3 (FR10). Nothing is thrown (design D3). Error
 * order is deterministic (NFR-R1): stages in pipeline declaration order.
 *
 * <p>Implements FR10, UX2, D6 of add-agent-executor.
 */
public final class ApiExecutorRule {

    private ApiExecutorRule() {}

    /**
     * Validates every stage's executor type against the {@code api}-rejection
     * rule: a stage with an {@code agent-cli} executor yields no error; each
     * {@code api}-executor stage yields exactly one located {@link ConfigError}.
     *
     * <p>Implements FR10, UX2, D6 of add-agent-executor.
     *
     * @param stages the stages in exactly the {@code pipeline.yaml} declaration
     *     order, as carried by {@link PipelineDefinition#stages()}
     * @return every located {@code api}-executor problem, in pipeline order;
     *     immutable, possibly empty
     */
    public static List<ConfigError> validate(List<StageDefinition> stages) {
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : stages) {
            if (stage.executor().type() == ExecutorType.API) {
                errors.add(
                        new ConfigError(
                                manifestPath(stage.name()),
                                "executor.type",
                                "api executor is not yet supported; 'agent-cli' is the only supported executor type currently"));
            }
        }
        return List.copyOf(errors);
    }

    private static String manifestPath(String stageName) {
        return "stages/%s/stage.yaml".formatted(stageName);
    }
}
