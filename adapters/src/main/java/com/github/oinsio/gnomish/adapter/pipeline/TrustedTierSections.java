package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.app.ConnectionProfiles;
import com.github.oinsio.gnomish.app.port.pipeline.ConfiguredDesignatorKinds;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.ArrayList;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The trusted-tier inputs {@link PipelineLoader} reads off the parsed {@code config.yaml} before the
 * model is built (FR1, FR3 of add-base-ref-resolution): the {@code task-branch.base} subsection, the
 * declared {@code tracker.type}, and the designator kinds the configured adapter extracts. Each
 * reader tolerates an absent or unparseable section — the parse tier already reported it — so the
 * base section and the designator seam run whatever state the rest of the tree is in (UX1).
 *
 * <p>Implements FR1, FR3 of add-base-ref-resolution.
 */
record TrustedTierSections(@Nullable ConfigDto config) {

    /** The {@code task-branch.base} subsection, or null when either level of it is absent. */
    @Nullable
    BaseDto baseSection() {
        if (config == null || config.taskBranch() == null) {
            return null;
        }
        return config.taskBranch().base();
    }

    /**
     * The declared {@code tracker.type}, or null when there is no tracker section or no type in
     * it. A single ternary rather than an early-return {@code if}: this class' one call site
     * (PipelineLoader) always pairs this with {@link #designatorKinds}, which independently
     * branches on the exact same "no tracker section" condition and forces {@code Set.of()}
     * whenever it holds — so {@code DesignatorAllowedBasesSeam} short-circuits on the empty kinds
     * before ever reading a null return here, making a two-branch {@code if}/{@code return}
     * PIT-unkillable on its null arm (no externally observable effect through that one caller).
     * The single-expression form collapses both arms into the one return PIT can mutate, which
     * the real-type arm's own tests already kill.
     */
    @Nullable
    String trackerType() {
        return config == null || config.tracker() == null
                ? null
                : config.tracker().type();
    }

    /**
     * The kinds the configured adapter extracts, asked on a scratch mapping of the parsed {@code
     * tracker} section: the mapping tier proper runs later and reports the section's own problems,
     * so the ones this pass would repeat are discarded. No section, no adapter, no kinds.
     */
    Set<String> designatorKinds(ConnectionProfiles profiles, ConfiguredDesignatorKinds designatorKinds) {
        if (config == null) {
            return Set.of();
        }
        TrackerConfig tracker = TrackerConfigMapper.map(config.tracker(), profiles, new ArrayList<>());
        return tracker == null ? Set.of() : designatorKinds.forTracker(tracker);
    }
}
