package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.app.base.BaseDesignatorMapping;
import com.github.oinsio.gnomish.baseref.BaseDefinition;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The one check that spans the two halves of per-task base selection (FR3, UX1 of
 * add-base-ref-resolution, design D5): the tracker adapter's rule for extracting a task's {@code
 * base} designator, and the {@code task-branch.base.allowed} list its values are graded against.
 *
 * <p>Either half alone is a legitimate configuration — an allowed list with no rule is a project
 * that lists its bases without letting tasks choose, and neither half is a project that has not
 * started. The combination "a rule extracts kind {@code base}, and the list is empty" is not: an
 * empty list accepts nothing, so every value the rule ever produces would be rejected as not
 * allowed, and every task carrying a label would park. That is the Kubernetes "selector cannot match its template"
 * class of error — a configuration that can only ever fail — and it is worth a load error at
 * startup rather than a stream of parked tasks later.
 *
 * <p>The resolver keeps its own defensive arm for the same case (an empty list plus a designator is
 * underdetermined input), so bypassing this check leaves the runtime fail-closed rather than
 * branching from an unvetted ref.
 *
 * <p>The check lives beside the loader rather than in the adapter because neither half's owner can
 * see the other: the adapter owns the rule and knows nothing of {@code task-branch.base.allowed},
 * and {@code :baseref} owns the allowed bases and knows nothing of trackers. The loader is where both are in hand.
 *
 * <p>Implements FR3, UX1 of add-base-ref-resolution.
 */
final class DesignatorAllowedBasesSeam {

    /** FR3: the {@code config.yaml} location stamped onto the problem. */
    private static final String FILE = "config.yaml";

    private DesignatorAllowedBasesSeam() {}

    /**
     * Checks the rule against the allowed bases.
     *
     * @param trackerType the declared {@code tracker.type}, or {@code null} when the section names
     *     none — the rule's location is then named generically, since there is no subsection to name
     * @param configuredDesignatorKinds the designator kinds the configured tracker adapter reports
     *     it extracts; empty when no adapter is configured or none declares a rule
     * @param base the mapped base definition
     * @return the one located error, or an empty list when the pair is coherent
     */
    static List<ConfigError> check(
            @Nullable String trackerType, Set<String> configuredDesignatorKinds, BaseDefinition base) {
        if (!configuredDesignatorKinds.contains(BaseDesignatorMapping.BASE_KIND)
                || !base.allowedBases().isEmpty()) {
            return List.of();
        }
        return List.of(new ConfigError(
                FILE,
                ruleLocation(trackerType),
                ("the tracker adapter extracts designator kind '%s', but task-branch.base.allowed declares no entry, "
                                + "so the rule can only ever reject a task's selection; "
                                + "add a task-branch.base.allowed entry or remove the rule")
                        .formatted(BaseDesignatorMapping.BASE_KIND)));
    }

    /** Where the rule is declared: the adapter's own subsection, named after the tracker type. */
    private static String ruleLocation(@Nullable String trackerType) {
        if (trackerType == null) {
            return "tracker.designators." + BaseDesignatorMapping.BASE_KIND;
        }
        return "tracker.%s.designators.%s".formatted(trackerType, BaseDesignatorMapping.BASE_KIND);
    }
}
