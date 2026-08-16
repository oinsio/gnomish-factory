package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.sandbox.AdapterBinding;
import com.github.oinsio.gnomish.sandbox.BindingProperties;
import com.github.oinsio.gnomish.sandbox.BindingResolver;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.SandboxReconciler;
import com.github.oinsio.gnomish.sandbox.Segment;
import com.github.oinsio.gnomish.sandbox.SegmentPlanner;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Decides how a git-mode run executes (the integration pass of
 * add-sandbox-core): resolves the operator's per-stage bindings ({@code
 * BindingResolver}, container by default — D13), plans the segments, reconciles
 * every stage's repo-declared needs against its binding's passport fail-closed
 * (FR14, UX2), and gates the container path on its two prerequisites — a
 * configured image and a reachable Docker runtime — refusing with one error
 * naming the ways out rather than falling back to host silently (G2, D13).
 *
 * <p>Mixed host/container bindings within one pipeline are refused for now:
 * the run-level round protocol (single round commit vs snapshot-first) is
 * mode-wide, and no supported scenario needs a mid-pipeline adapter switch —
 * an honest refusal beats a half-working hybrid. Segments still split within
 * container mode on {@code requires-fresh} (FR13).
 *
 * <p>Implements FR14, G2, UX2, D13 of add-sandbox-core.
 */
final class SandboxModeSelector {

    /** The run's execution shape: the resolved mode and the planned segments. */
    record Plan(Mode mode, List<Segment> segments) {

        enum Mode {
            HOST,
            CONTAINER
        }
    }

    private SandboxModeSelector() {}

    /**
     * Plans {@code definition}'s execution under the operator's bindings. {@code dockerAvailable}
     * answers the D13 container-runtime prerequisite; the composition root binds it to the real
     * {@code ContainerEnvironments::dockerAvailable} probe, daemon-free specs to a scripted
     * boolean. Injected rather than defaulted here (task 4.4, FR12b of split-into-modules): naming
     * the docker backend from a use case is exactly the adapter dependency the layering forbids,
     * and the probe was already a seam.
     *
     * @throws UsageException on an unmet stage need, a mixed-binding pipeline, or a container
     *     run without its prerequisites (image + Docker)
     */
    static Plan plan(
            PipelineDefinition definition,
            BindingProperties bindings,
            SandboxProperties sandbox,
            BooleanSupplier dockerAvailable) {
        BindingResolver resolver = resolver(bindings);
        List<Segment> segments = new SegmentPlanner(resolver).plan(definition);
        reconcile(segments);

        boolean container = segments.stream().anyMatch(s -> s.binding() == AdapterBinding.CONTAINER);
        boolean host = segments.stream().anyMatch(s -> s.binding() == AdapterBinding.HOST);
        if (container && host) {
            throw new UsageException(
                    "mixed host/container stage bindings within one pipeline are not supported: bind every stage"
                            + " to one adapter via factory.bindings.* (per-stage overrides may still differ between"
                            + " pipelines)");
        }
        if (container) {
            requireContainerPrerequisites(sandbox, dockerAvailable);
            return new Plan(Plan.Mode.CONTAINER, segments);
        }
        return new Plan(Plan.Mode.HOST, segments);
    }

    private static BindingResolver resolver(BindingProperties bindings) {
        try {
            return new BindingResolver(bindings);
        } catch (IllegalArgumentException e) {
            throw new UsageException("invalid factory.bindings configuration: " + e.getMessage());
        }
    }

    /** Fail-closed needs-vs-passport reconciliation, one clear error naming the unmet need (FR14, UX2). */
    private static void reconcile(List<Segment> segments) {
        var reconciler = new SandboxReconciler();
        for (Segment segment : segments) {
            for (StageDefinition stage : segment.stages()) {
                List<String> unmet = reconciler.unmetNeeds(
                        stage.executor().sandbox(), segment.binding().passport());
                if (!unmet.isEmpty()) {
                    throw new UsageException("stage \"" + stage.name() + "\" declares sandbox needs the bound \""
                            + segment.binding().configName() + "\" adapter does not satisfy: "
                            + String.join(", ", unmet)
                            + " — bind an adapter whose passport satisfies them (factory.bindings.*)");
                }
            }
        }
    }

    /** The D13 refusal: container is the default, and its absence names the two ways out — never silent host. */
    private static void requireContainerPrerequisites(SandboxProperties sandbox, BooleanSupplier dockerAvailable) {
        String image = sandbox.image();
        if (image == null || image.isBlank()) {
            throw new UsageException(
                    "stages bind the container adapter (the default) but factory.sandbox.image is not set — set the"
                            + " sandbox image (see docs/examples/sandbox-image/), or explicitly bind host mode"
                            + " (factory.bindings.default=host) if this trusted environment should run unsandboxed");
        }
        if (!dockerAvailable.getAsBoolean()) {
            throw new UsageException(
                    "stages bind the container adapter (the default) but the Docker runtime is unreachable — install"
                            + " or start Docker, or explicitly bind host mode (factory.bindings.default=host) if this"
                            + " trusted environment should run unsandboxed");
        }
    }
}
