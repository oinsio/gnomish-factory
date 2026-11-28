package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.ArrayList;
import java.util.List;

/**
 * Load-time routing of the agent's settings to the agent executor's settings judge (task 9.1 of
 * add-agent-executor; FR7, design D6 of extract-agent-round-runner): the {@code settings} of every
 * {@code agent-cli} executor block — a stage's own, and a {@code judge} check's, whose settings are
 * its block's (design D12 of extract-agent-round-runner), regardless of the parent stage's executor
 * type. A judge block of another type is graded by its own executor's schema, not this one.
 *
 * <p>What the settings may hold is not this class's knowledge: the agent executor's describe owns its
 * settings schema (the Claude dialect's, in {@code :adapters:agent}), and the composition root hands
 * its judge in as a {@link CheckSettingsValidator}, bound by a method reference since this module
 * cannot reach that one. This class only finds the settings the judge must see and the location each
 * is reported at — {@code executor.settings} or {@code verify[<i>].executor.settings} of the stage
 * manifest.
 * A load handed no judge grades no agent settings, the same way an empty check registry grades no
 * check's settings.
 *
 * <p>{@code api} stage executor settings are never inspected: {@code api} stages are rejected at
 * startup by a separate fail-fast check (task 9.2, FR10 of add-agent-executor).
 *
 * <p>Lives in {@code adapter.pipeline}, next to its only caller {@link PipelineModelBuilder}, so that
 * {@code :adapters:agent} depends on this module and never the reverse (task 10.2 of
 * split-into-modules).
 *
 * <p>Implements FR11, UX2, D7 of add-agent-executor; FR7, D6, FR13 of extract-agent-round-runner.
 */
public final class AgentSettingsValidator {

    private AgentSettingsValidator() {}

    /**
     * Grades the settings of every {@code agent-cli} executor block, a stage's and a judge
     * check's, across the whole pipeline with {@code judge}.
     *
     * @param pipeline the mapped, domain-validated pipeline definition
     * @param judge the agent executor's settings judge, or {@link #UNJUDGED} to grade nothing; never null
     * @return every located settings problem, in stage-then-check order; immutable, possibly empty
     */
    public static List<ConfigError> validate(PipelineDefinition pipeline, CheckSettingsValidator judge) {
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : pipeline.stages()) {
            String manifest = "stages/%s/stage.yaml".formatted(stage.name());
            grade(manifest, "executor", stage.executor(), judge, errors);
            List<VerifyCheck> checks = stage.verify();
            for (int index = 0; index < checks.size(); index++) {
                if (checks.get(index) instanceof VerifyCheck.Judge check) {
                    grade(manifest, "verify[%d].executor".formatted(index), check.executor(), judge, errors);
                }
            }
        }
        return List.copyOf(errors);
    }

    private static void grade(
            String manifest,
            String where,
            StageDefinition.Executor block,
            CheckSettingsValidator judge,
            List<ConfigError> errors) {
        if (block.type() == ExecutorType.AGENT_CLI) {
            errors.addAll(judge.validate(manifest, where + ".settings", block.settings()));
        }
    }

    /** The judge of a load that grades no agent settings: every settings map is accepted. */
    public static final CheckSettingsValidator UNJUDGED = (file, where, settings) -> List.of();
}
