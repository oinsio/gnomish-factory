package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;
import java.util.Map;

/**
 * One pipeline stage as declared in {@code stages/<name>/stage.yaml} — the
 * machine-readable counterpart of the eight IDEF0/ICOM + Quality Control
 * sections of the stage contract ({@code .claude/rules/stage-description.md}):
 *
 * <ol>
 *   <li>Purpose → {@code purpose}</li>
 *   <li>Input → {@code inputs} (order-preserving)</li>
 *   <li>Output → {@code outputs} (order-preserving)</li>
 *   <li>Control → {@code instructionsRef}</li>
 *   <li>Mechanism → {@code executor} (type + pinned model + opaque settings)</li>
 *   <li>Quality Control → {@code verify} (order-preserving: cheap checks first,
 *       {@code judge} last is the author's ordering, faithfully kept)</li>
 *   <li>Failure &amp; Escalation → {@code limits} (already resolved, FR7)</li>
 *   <li>Advancement → {@code advancement}</li>
 * </ol>
 *
 * <p>The record is inert, immutable data: lists are defensively copied and
 * unmodifiable, and no semantic rule is enforced here — cross-stage and
 * local-sanity validation is the pure validators' concern (design D6, tasks
 * 4.2–4.4), reported as located {@link ConfigError}s, never a throwing
 * constructor.
 *
 * <p>Implements FR2 of load-pipeline-config.
 *
 * @param name the stage name, unique across the pipeline (uniqueness validated
 *     by task 4.2, not here)
 * @param purpose what the stage transforms and why it exists (§1)
 * @param inputs the artifacts the stage consumes, in declaration order (§2)
 * @param outputs the artifacts the stage produces, in declaration order (§3)
 * @param executor the Mechanism section (§5)
 * @param instructionsRef path of the stage's instructions file, relative to the
 *     {@code .gnomish/} root (§4) — a plain string: the domain never touches
 *     the filesystem (D1); existence is checked by the adapter (FR6, task 6.3)
 * @param verify the ordered Quality Control check list (§6)
 * @param limits the resolved autonomy limits (§7, FR7)
 * @param advancement how the pipeline advances after verification passes (§8)
 */
public record StageDefinition(
        String name,
        String purpose,
        List<ArtifactInput> inputs,
        List<ArtifactOutput> outputs,
        Executor executor,
        String instructionsRef,
        List<VerifyCheck> verify,
        AutonomyLimits limits,
        AdvancementMode advancement) {

    public StageDefinition {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        verify = List.copyOf(verify);
    }

    /**
     * The Mechanism section (§5): the executor kind, the model pinned in the
     * manifest so any instance reproduces the stage identically — required for
     * every executor type, never left to a CLI default — and opaque executor
     * settings. Per Q1/D5a both {@code model} and {@code settings} are carried
     * opaque: the settings map holds plain JDK types (String/Number/Boolean/
     * List/Map, mapped from the YAML tree by the adapter) so the domain stays
     * Jackson-free, and model non-blankness is the FR11 validators' concern
     * (task 4.4), carried here unvalidated.
     *
     * <p>Implements FR2 and carries the FR11 fields of load-pipeline-config.
     *
     * @param type the executor kind
     * @param model the model pinned for reproducibility (FR11)
     * @param settings opaque executor settings, possibly empty; immutable
     */
    public record Executor(ExecutorType type, String model, Map<String, Object> settings) {

        public Executor {
            settings = Map.copyOf(settings);
        }
    }
}
