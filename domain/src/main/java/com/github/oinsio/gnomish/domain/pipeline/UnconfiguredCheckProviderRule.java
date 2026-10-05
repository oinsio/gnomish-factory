package com.github.oinsio.gnomish.domain.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The pure fail-fast rule for {@code external} checks this factory cannot serve (FR3, design D3 of
 * remove-interactive-console): every {@code external} check whose provider has no {@code
 * factory.check.<provider>} section in the factory's configuration is rejected at startup, before
 * any dialog — the same treatment {@link ApiExecutorRule} gives an {@code api} stage. Without a
 * section no client can be built for the provider, so accepting the manifest would produce a
 * pipeline whose verification can never run.
 *
 * <p>"Configured" is a fact about this factory instance, not about the manifest or the classpath,
 * so the configured set is an input here rather than something the rule can know. Whether a
 * provider is <em>discovered</em> at all is the load seam's question (an undiscovered provider is
 * reported there as unknown); this rule only asks whether the operator wrote its section.
 *
 * <p>Problems are located {@link ConfigError}s naming the stage manifest and the check's {@code
 * verify[i].provider} field, with a message naming the provider, the section to write and the
 * configured set (NFR-O1) — the text an operator reads on stderr before exit code 3. Nothing is
 * thrown (design D3 of load-pipeline-config). Error order is deterministic (NFR-R1): stages in
 * pipeline declaration order, checks in {@code verify} order within a stage.
 *
 * <p>Implements FR3, NFR-O1, D3 of remove-interactive-console.
 */
public final class UnconfiguredCheckProviderRule {

    private UnconfiguredCheckProviderRule() {}

    /**
     * Validates every {@code external} check's provider against the configured providers.
     *
     * <p>Implements FR3, NFR-O1 of remove-interactive-console.
     *
     * @param stages the stages in exactly the {@code pipeline.yaml} declaration order, as carried by
     *     {@link PipelineDefinition#stages()}
     * @param configuredProviders the providers with a {@code factory.check.<provider>} section in
     *     this factory's configuration; empty when the operator configured none
     * @return every located unconfigured-provider problem, in pipeline-then-check order; immutable,
     *     possibly empty
     */
    public static List<ConfigError> validate(List<StageDefinition> stages, Set<String> configuredProviders) {
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : stages) {
            List<VerifyCheck> checks = stage.verify();
            for (int index = 0; index < checks.size(); index++) {
                if (checks.get(index) instanceof VerifyCheck.External external
                        && !configuredProviders.contains(external.provider())) {
                    errors.add(new ConfigError(
                            "stages/%s/stage.yaml".formatted(stage.name()),
                            "verify[%d].provider".formatted(index),
                            "check provider '%s' has no factory.check.%s section in this factory's configuration; configured providers: %s"
                                    .formatted(
                                            external.provider(),
                                            external.provider(),
                                            new TreeSet<>(configuredProviders))));
                }
            }
        }
        return List.copyOf(errors);
    }
}
