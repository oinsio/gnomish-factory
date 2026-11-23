package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;

/**
 * The catalog-free half of the role rule over a {@code judge} check's executor block (design D12 of
 * extract-agent-round-runner): the executor a judge block resolves to must serve role {@code
 * verdict}. Two block types are judged here because what they resolve to is fixed by the factory,
 * not by the law:
 *
 * <ul>
 *   <li>{@code command} resolves to the {@link BuiltInExecutors#COMMAND} built-in, whose describe
 *       lists role {@code transform} only;</li>
 *   <li>{@code api} resolves to no executor at all ({@link ApiExecutorRule}).</li>
 * </ul>
 *
 * <p>The other two are judged where their describe is known: {@code agent-cli} resolves to {@link
 * BuiltInExecutors#CLAUDE}, whose describe — the Claude dialect's — lists {@code verdict}; a {@code
 * program} block resolves to the law's declaration it names, whose describe answer the describe
 * preflight holds against role {@code verdict} ({@code DescribeAgreement} in {@code :application}).
 *
 * <p>An error is located at the block's {@code verify[<i>].executor.type} of the stage manifest and
 * names the executor and the role; nothing is thrown. Called by {@link StageSanityRule}, after the
 * block's {@link StageMechanismRule}.
 *
 * <p>Implements FR13, UX1 of extract-agent-round-runner.
 */
final class JudgeRoleRule {

    private JudgeRoleRule() {}

    static void validate(String manifest, String where, StageDefinition.Executor executor, List<ConfigError> errors) {
        String reason =
                switch (executor.type()) {
                    case COMMAND ->
                        "executor type 'command' resolves to the built-in executor '%s', which serves only role 'transform'"
                                .formatted(BuiltInExecutors.COMMAND);
                    case API -> "executor type 'api' resolves to no executor";
                    case AGENT_CLI, PROGRAM -> null;
                };
        if (reason != null) {
            errors.add(new ConfigError(
                    manifest,
                    where + ".type",
                    reason + "; a judge check needs an executor that serves role 'verdict'"
                            + " (type agent-cli, or a program whose describe lists 'verdict')"));
        }
    }
}
