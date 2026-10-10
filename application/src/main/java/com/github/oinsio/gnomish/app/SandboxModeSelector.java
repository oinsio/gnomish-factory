package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry;
import com.github.oinsio.gnomish.sandbox.BindingNames;
import com.github.oinsio.gnomish.sandbox.BindingProperties;
import com.github.oinsio.gnomish.sandbox.BindingResolver;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.SandboxReconciler;
import com.github.oinsio.gnomish.sandbox.Segment;
import com.github.oinsio.gnomish.sandbox.SegmentPlanner;
import java.nio.file.Path;
import java.util.List;

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
 * <p>Every refusal names the one place its fix goes: the bindings and the sandbox image are
 * sandbox-boundary keys, read only from the resolved project's own {@code project.yaml} (FR6,
 * NFR-S1 of add-project-registry), so the advice names that file by its path rather than a
 * command-line option the configuration loader would refuse.
 *
 * <p>An instance over the four inputs of the decision — the operator's bindings, the sandbox
 * config, the classpath-discovered binding registry and the container-runtime probe — built once
 * by the composition root (design D22 of supervise-daemon-loops-and-embed-dashboard), so no caller
 * supplies its own four inputs: {@code ContainerSupports} (manual runs) and {@code TakeWorkRouter}
 * (take and serve) both ask this one object. ADR 0010's three answers: (a) without the probe the
 * other three cannot decide fail-closed; (b) {@link #plan} resolves the bindings, reconciles stage
 * needs and refuses; (c) "execution mode" is the glossary's term. The sandbox config has a second
 * reader, the container support factory (the box equipment): one bound configuration record, two
 * readers — not a duplicate to thread through here.
 *
 * <p>Implements FR14, G2, UX2, D13 of add-sandbox-core; FR6, NFR-S1 of add-project-registry; FR18
 * of supervise-daemon-loops-and-embed-dashboard.
 */
public final class SandboxModeSelector {

    /** The run's execution shape: the resolved mode and the planned segments. */
    record Plan(Mode mode, List<Segment> segments) {

        enum Mode {
            HOST,
            CONTAINER
        }
    }

    private final BindingProperties bindings;
    private final SandboxProperties sandbox;
    private final AdapterBindingRegistry registry;
    private final ContainerRuntimeProbe runtimeProbe;

    /**
     * @param bindings the operator's per-stage bindings, read from the resolved project's own
     *     {@code project.yaml} (FR6 of add-project-registry); never null
     * @param sandbox the operator sandbox config, read for the image prerequisite; never null
     * @param registry the bindings the classpath contributed (D6 of open-adapter-binding-registry):
     *     the composition root passes the discovered registry, specs one of their own; never null
     * @param runtimeProbe answers the D13 container-runtime prerequisite: the composition root binds
     *     the Docker adapter's probe, daemon-free specs a scripted answer. Injected rather than
     *     defaulted here (task 4.4, FR12b of split-into-modules): naming the docker backend from a
     *     use case is exactly the adapter dependency the layering forbids; never null
     */
    public SandboxModeSelector(
            BindingProperties bindings,
            SandboxProperties sandbox,
            AdapterBindingRegistry registry,
            ContainerRuntimeProbe runtimeProbe) {
        this.bindings = bindings;
        this.sandbox = sandbox;
        this.registry = registry;
        this.runtimeProbe = runtimeProbe;
    }

    /**
     * Plans {@code definition}'s execution under the operator's bindings.
     *
     * @param definition the pipeline the run advances through; never null
     * @param clone the registered clone the run works in; its project file is the one every
     *     refusal names as the place for the fix
     * @return the resolved mode and the planned segments; never null
     * @throws UsageException on an unmet stage need, a mixed-binding pipeline, or a container
     *     run without its prerequisites (image + Docker)
     */
    Plan plan(PipelineDefinition definition, RegisteredClone clone) {
        Path projectFile = clone.layout().config();
        BindingResolver resolver = resolver(bindings, registry, projectFile);
        List<Segment> segments = new SegmentPlanner(resolver).plan(definition);
        reconcile(segments, projectFile);

        boolean container = boundTo(segments, BindingNames.CONTAINER);
        boolean host = boundTo(segments, BindingNames.HOST);
        if (container && host) {
            throw new UsageException(
                    "mixed host/container stage bindings within one pipeline are not supported: bind every stage"
                            + " to one adapter via factory.bindings.* in " + projectFile + " (per-stage overrides may"
                            + " still differ between pipelines)");
        }
        if (container) {
            requireContainerPrerequisites(projectFile);
            return new Plan(Plan.Mode.CONTAINER, segments);
        }
        return new Plan(Plan.Mode.HOST, segments);
    }

    /**
     * Host-vs-container branching by config-name identity, not by enum constant (D3 of
     * open-adapter-binding-registry). The docker-prerequisite gate below stays keyed to the actual
     * {@code container} binding rather than to "any isolated binding": a future VM backend is
     * isolated but is not Docker, and keying on isolation would misapply the prerequisite to it.
     */
    private static boolean boundTo(List<Segment> segments, String configName) {
        return segments.stream().anyMatch(s -> s.binding().configName().equals(configName));
    }

    private static BindingResolver resolver(
            BindingProperties bindings, AdapterBindingRegistry registry, Path projectFile) {
        try {
            return new BindingResolver(bindings, registry);
        } catch (IllegalArgumentException e) {
            throw new UsageException(
                    "invalid factory.bindings configuration in " + projectFile + ": " + e.getMessage());
        }
    }

    /** Fail-closed needs-vs-passport reconciliation, one clear error naming the unmet need (FR14, UX2). */
    private static void reconcile(List<Segment> segments, Path projectFile) {
        var reconciler = new SandboxReconciler();
        for (Segment segment : segments) {
            for (StageDefinition stage : segment.stages()) {
                List<String> unmet = reconciler.unmetNeeds(
                        stage.executor().sandbox(), segment.binding().passport());
                if (!unmet.isEmpty()) {
                    throw new UsageException("stage \"" + stage.name() + "\" declares sandbox needs the bound \""
                            + segment.binding().configName() + "\" adapter does not satisfy: "
                            + String.join(", ", unmet)
                            + " — bind an adapter whose passport satisfies them (factory.bindings.* in "
                            + projectFile + ")");
                }
            }
        }
    }

    /** The D13 refusal: container is the default, and its absence names the two ways out — never silent host. */
    private void requireContainerPrerequisites(Path projectFile) {
        String image = sandbox.image();
        if (image == null || image.isBlank()) {
            throw new UsageException(
                    "stages bind the container adapter (the default) but factory.sandbox.image is not set — set the"
                            + " sandbox image in " + projectFile + " (see docs/examples/sandbox-image/), or"
                            + hostOptOut(projectFile));
        }
        if (!runtimeProbe.available()) {
            throw new UsageException(
                    "stages bind the container adapter (the default) but the Docker runtime is unreachable — install"
                            + " or start Docker, or" + hostOptOut(projectFile));
        }
    }

    /** The explicit host opt-out, spelled as the project-file line an operator adds (NFR-S1). */
    private static String hostOptOut(Path projectFile) {
        return " explicitly bind host mode with factory.bindings.default: host in " + projectFile
                + " if this trusted environment should run unsandboxed";
    }
}
