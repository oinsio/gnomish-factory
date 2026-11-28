package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where a {@code program} verdict check can be bound in this change (design D18 of
 * migrate-verify-to-executor-contract, "A {@code program} check outside a fresh box"; proposal NG14):
 * only through the fresh box of {@link ProgramCheckBinding}, so only in container mode and only for a
 * check that verifies {@code fresh-box}. This is the owner that knows which placements bind; every
 * other placement of a check naming one of the law's programs — host mode, or the {@code same-box}
 * default of a check without credentials — is a located {@link ConfigError} naming the check (its
 * manifest and {@code verify[<i>]}) and the executor, which run assembly joins to the describe
 * refusal's errors. A {@code judge} check whose block is a {@code program} is placed as its check
 * round is ({@link VerifyCheck.Judge#asExecutorCheck()}: a fresh box), so it is refused in host mode
 * alike (design D2 of extract-agent-round-runner). Pure.
 *
 * <p>The first change that binds a {@code program} check outside the fresh box (NG14's pickup) lifts
 * the refusal for its medium.
 *
 * <p>Implements FR1, FR3 of migrate-verify-to-executor-contract; FR4 of extract-agent-round-runner.
 */
public final class ProgramCheckPlacement {

    private ProgramCheckPlacement() {}

    /**
     * The checks of {@code definition} naming a program that this run could not bind.
     *
     * @param definition the run's law; never null
     * @param freshBoxes whether the run has fresh boxes: container mode
     * @return one located error per such check, in pipeline order; empty when every one binds
     */
    public static List<ConfigError> refusals(PipelineDefinition definition, boolean freshBoxes) {
        Set<String> programs =
                definition.executors().stream().map(ExecutorDeclaration::name).collect(Collectors.toSet());
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : definition.stages()) {
            List<VerifyCheck> checks = stage.verify();
            for (int index = 0; index < checks.size(); index++) {
                var check = asCheckRound(checks.get(index));
                if (programs.contains(check.executorName())
                        && (!freshBoxes || check.verifyIn() == VerifyCheck.VerifyIn.SAME_BOX)) {
                    errors.add(new ConfigError(
                            "stages/%s/stage.yaml".formatted(stage.name()),
                            "verify[%d]".formatted(index),
                            "check '%s' names program executor '%s', which runs only in a fresh box: %s"
                                    .formatted(check.checkId(), check.executorName(), why(freshBoxes))));
                }
            }
        }
        return errors;
    }

    /** The check as its check round names it: a judge by the executor its block resolves to. */
    private static VerifyCheck.Executor asCheckRound(VerifyCheck check) {
        return switch (check) {
            case VerifyCheck.Executor executor -> executor;
            case VerifyCheck.Judge judge -> judge.asExecutorCheck();
        };
    }

    private static String why(boolean freshBoxes) {
        return freshBoxes
                ? "the check verifies same-box; declare verify-in: fresh-box"
                : "a host-mode run has no fresh box; run the stage in container mode";
    }
}
