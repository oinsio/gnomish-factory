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
 * add-agent-executor; FR7, design D6 of extract-agent-round-runner): every {@code agent-cli} stage
 * executor's {@code settings}, and every {@code judge} check's {@code settings} regardless of its
 * parent stage's executor type — a judge check always runs its own agent round (FR8 of
 * add-agent-executor), so an {@code api} or {@code command} stage's judge check is not exempt.
 *
 * <p>What the settings may hold is not this class's knowledge: the agent executor's describe owns its
 * settings schema (the Claude dialect's, in {@code :adapters:agent}), and the composition root hands
 * its judge in as a {@link CheckSettingsValidator}, bound by a method reference since this module
 * cannot reach that one. This class only finds the settings the judge must see and the location each
 * is reported at — {@code executor.settings} or {@code verify[<i>].settings} of the stage manifest.
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
 * <p>Implements FR11, UX2, D7 of add-agent-executor; FR7, D6 of extract-agent-round-runner.
 */
public final class AgentSettingsValidator {

    private AgentSettingsValidator() {}

    /**
     * Grades every {@code agent-cli} stage executor's settings and every judge check's settings
     * across the whole pipeline with {@code judge}.
     *
     * @param pipeline the mapped, domain-validated pipeline definition
     * @param judge the agent executor's settings judge, or {@link #UNJUDGED} to grade nothing; never null
     * @return every located settings problem, in stage-then-check order; immutable, possibly empty
     */
    public static List<ConfigError> validate(PipelineDefinition pipeline, CheckSettingsValidator judge) {
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : pipeline.stages()) {
            String manifest = "stages/%s/stage.yaml".formatted(stage.name());
            if (stage.executor().type() == ExecutorType.AGENT_CLI) {
                errors.addAll(judge.validate(
                        manifest, "executor.settings", stage.executor().settings()));
            }
            List<VerifyCheck> checks = stage.verify();
            for (int index = 0; index < checks.size(); index++) {
                if (checks.get(index) instanceof VerifyCheck.Judge check) {
                    errors.addAll(judge.validate(manifest, "verify[%d].settings".formatted(index), check.settings()));
                }
            }
        }
        return List.copyOf(errors);
    }

    /** The judge of a load that grades no agent settings: every settings map is accepted. */
    public static final CheckSettingsValidator UNJUDGED = (file, where, settings) -> List.of();
}
