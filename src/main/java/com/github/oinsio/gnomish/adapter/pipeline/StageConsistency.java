package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.pipeline.GnomishFiles.RawStage;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code pipeline.yaml} &harr; stage-directory consistency cross-check (task
 * 6.2, FR6). Given the explicit stage-name list declared in {@code pipeline.yaml}
 * (the order source of truth, FR3) and the stage directories {@link GnomishFiles}
 * discovered on disk, it reconciles the two and reports each mismatch as a located
 * {@link ConfigError} (NFR-O1, UX2). Because the reconciliation depends on what is
 * physically present on disk (a {@link RawStage} with {@code null} text is a directory
 * that lacks its {@code stage.yaml}), this rule lives in the adapter I/O tier, not
 * the pure domain (design D6). The check itself is a pure function over the two lists,
 * so it is fully unit-testable without the filesystem.
 *
 * <p>Two rules:
 *
 * <ul>
 *   <li><b>Missing manifest.</b> A stage named in {@code pipeline.yaml} that has no
 *       {@code stages/<name>/stage.yaml} — either no {@code stages/<name>/} directory
 *       at all (no matching {@link RawStage}), or a directory whose manifest is absent
 *       (a {@link RawStage} with {@code null} text). Both mean the same thing to the
 *       author: the referenced stage has no manifest. Located on {@code pipeline.yaml}
 *       (which makes the unfulfilled reference) and naming the expected manifest path
 *       {@code stages/<name>/stage.yaml} so the fix is unambiguous.</li>
 *   <li><b>Dangling directory.</b> A discovered {@code stages/<name>/} directory whose
 *       name {@code pipeline.yaml} never references (no dangling definitions, FR6).
 *       Located on the directory's expected manifest path {@code stages/<name>/stage.yaml}
 *       and naming the unreferenced stage. A dangling directory is reported as dangling
 *       even when it also lacks a manifest — it is not in the pipeline, so a
 *       missing-manifest complaint would misdirect the author.</li>
 * </ul>
 *
 * <p>Deterministic order (NFR-R1, UX1): all missing-manifest errors first in
 * {@code pipeline.yaml} declaration order, then all dangling errors in discovered
 * (sorted-by-name) order, so every problem is fixable in a single pass. Stage-name
 * emptiness and uniqueness <em>within</em> {@code pipeline.yaml} are a separate concern
 * ({@code StageOrderRule}, task 4.2); duplicate pipeline names do not change this
 * cross-check's verdict.
 *
 * <p>Implements FR6 of load-pipeline-config.
 */
public final class StageConsistency {

    private StageConsistency() {}

    /**
     * Reconciles the declared pipeline stage names against the discovered stage
     * directories.
     *
     * <p>Implements FR6 of load-pipeline-config.
     *
     * @param pipelineStageNames the stage names in {@code pipeline.yaml} declaration
     *     order (FR3)
     * @param discovered the stage directories {@link GnomishFiles} found on disk, each
     *     carrying its {@code stage.yaml} text or {@code null} when the manifest is
     *     absent
     * @return every located consistency error — missing manifests (pipeline order) then
     *     dangling directories (discovered order); an immutable, possibly empty list
     */
    public static List<ConfigError> check(List<String> pipelineStageNames, List<RawStage> discovered) {
        Map<String, RawStage> byName = indexByName(discovered);
        Set<String> referenced = new HashSet<>(pipelineStageNames);

        List<ConfigError> errors = new ArrayList<>();
        for (String name : pipelineStageNames) {
            RawStage stage = byName.get(name);
            if (stage == null || stage.text() == null) {
                errors.add(missingManifest(name));
            }
        }
        for (RawStage stage : discovered) {
            if (!referenced.contains(stage.name())) {
                errors.add(dangling(stage.name()));
            }
        }
        return List.copyOf(errors);
    }

    /**
     * Indexes discovered stages by directory name. Discovery already sorts directory
     * names uniquely (a filesystem cannot hold two entries of the same name), so a plain
     * last-wins map preserves every discovered stage.
     */
    private static Map<String, RawStage> indexByName(List<RawStage> discovered) {
        Map<String, RawStage> byName = new HashMap<>();
        for (RawStage stage : discovered) {
            byName.put(stage.name(), stage);
        }
        return byName;
    }

    private static ConfigError missingManifest(String name) {
        return new ConfigError(
                "pipeline.yaml",
                "stages[%s]".formatted(name),
                "pipeline stage '%s' has no manifest; expected stages/%s/stage.yaml".formatted(name, name));
    }

    private static ConfigError dangling(String name) {
        return new ConfigError(
                "stages/%s/stage.yaml".formatted(name),
                "stages/%s".formatted(name),
                "dangling stage directory '%s' is not referenced by pipeline.yaml".formatted(name));
    }
}
