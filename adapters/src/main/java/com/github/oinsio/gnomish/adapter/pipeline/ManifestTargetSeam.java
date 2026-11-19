package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.check.http.HttpExecutor;
import com.github.oinsio.gnomish.adapter.check.http.HttpTargetAdmission;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.httpegress.OperatorEgress;
import java.util.ArrayList;
import java.util.List;

/**
 * The load seam where a stage manifest's http targets meet the operator's {@code
 * factory.egress.in-process} scope (FR15, FR17, UX4, design D16 of
 * migrate-verify-to-executor-contract, the law-medium bullet). The manifest lives in the law tree
 * and the scope in operator configuration; the loader is the one place both are in reach ({@link
 * ConnectionAdmission} carries the scope down beside the tracker's endpoint declarations), so the
 * judgement runs here, over the mapped stages, and not behind a check provider's params validator
 * — that SPI never sees operator configuration and is removed by task 5.2.
 *
 * <p>Every check requesting the {@code http} built-in is judged by {@link HttpTargetAdmission}; a
 * refusal is a located load error at the check's {@code url}, aggregated with every other load
 * problem (NFR-R1 of load-pipeline-config).
 *
 * <p>Implements FR15, FR17, UX4 of migrate-verify-to-executor-contract.
 */
final class ManifestTargetSeam {

    private ManifestTargetSeam() {}

    /**
     * Judges the target of every {@code http} check of every stage.
     *
     * @param stages the mapped stages in pipeline order; never null
     * @param operator the operator's {@code in-process} scope; never null
     * @return every located refusal, in stage-then-check order; empty when load admits every target
     */
    static List<ConfigError> judge(List<StageDefinition> stages, OperatorEgress operator) {
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : stages) {
            List<VerifyCheck> checks = stage.verify();
            for (int index = 0; index < checks.size(); index++) {
                if (checks.get(index) instanceof VerifyCheck.Executor check
                        && check.executorName().equals(HttpExecutor.NAME)) {
                    String where = "verify[%d].%s"
                            .formatted(index, check.token() == VerifyCheck.Token.EXECUTOR ? "settings" : "params");
                    errors.addAll(HttpTargetAdmission.judge(
                            "stages/%s/stage.yaml".formatted(stage.name()), where, check.settings(), operator));
                }
            }
        }
        return List.copyOf(errors);
    }
}
