package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.TrackerReadVerdict;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry;
import com.github.oinsio.gnomish.sandbox.BindingNames;
import com.github.oinsio.gnomish.sandbox.BindingProperties;
import com.github.oinsio.gnomish.sandbox.BindingResolver;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * What {@code doctor} reads about one registered clone, each answer from the owner the run path
 * asks (FR1, FR4 of add-doctor-command): the pipeline law through the pipeline source, the stage
 * bindings through {@link BindingResolver}, the declared secrets through the tracker wiring and the
 * check equipment, the agent round's child environment as the run assembly composes it, and the
 * tracker adapter's read-only probe through the tracker wiring's relay. A
 * facade (ADR 0010) over owners the doctor package cannot reach — they are this package's own —
 * whose answers the doctor checks read together: (a) every read starts from the clone's loaded
 * definition; (b) it composes, never holds, a credential seam; (c) "doctor" is the glossary's term.
 *
 * <p>Implements FR1, FR3, FR4, NFR-S1 of add-doctor-command.
 */
@NullMarked
@Component
public final class DoctorProjectReadings {

    /**
     * Which adapters the clone's stages bind (design D3 of add-doctor-command).
     *
     * @param container whether any stage binds the container adapter
     * @param host whether any stage binds the host adapter
     */
    public record StageBindings(boolean container, boolean host) {}

    private final TrackerWiring trackerWiring;
    private final CheckEquipment checks;
    private final BindingProperties bindings;
    private final AdapterBindingRegistry registry;
    private final SandboxProperties sandbox;

    DoctorProjectReadings(
            TrackerWiring trackerWiring,
            CheckEquipment checks,
            BindingProperties bindings,
            AdapterBindingRegistry registry,
            SandboxProperties sandbox) {
        this.trackerWiring = trackerWiring;
        this.checks = checks;
        this.bindings = bindings;
        this.registry = registry;
        this.sandbox = sandbox;
    }

    /**
     * Loads and validates the clone's pipeline law from its working tree, as {@code run} loads it.
     *
     * @throws PipelineLoadFailedException carrying every located error, each rendered for the console
     * @throws IOException if the law cannot be read at all
     */
    public PipelineDefinition loadPipeline(RegisteredClone clone) throws IOException {
        LoadOutcome outcome = trackerWiring.pipelineSource().load(clone.clonePath());
        return switch (outcome) {
            case LoadOutcome.Loaded(var definition) -> definition;
            case LoadOutcome.Invalid(List<ConfigError> errors) ->
                throw new PipelineLoadFailedException(errors.stream()
                        .map(error -> error.render().forConsole())
                        .toList());
        };
    }

    /**
     * The adapters {@code definition}'s stages bind under the operator's bindings.
     *
     * @throws IllegalArgumentException if a configured binding name is not discovered
     */
    public StageBindings bindings(PipelineDefinition definition) {
        BindingResolver resolver = new BindingResolver(bindings, registry);
        List<String> bound = definition.stages().stream()
                .map(StageDefinition::name)
                .map(stage -> resolver.resolve(stage).configName())
                .toList();
        return new StageBindings(bound.contains(BindingNames.CONTAINER), bound.contains(BindingNames.HOST));
    }

    /**
     * Every secret a run of {@code definition} declares — the tracker adapter's, the configured check
     * providers' and the pipeline's own checks' — names only, each once, in that order.
     *
     * @throws UsageException if the {@code tracker} section names an unregistered adapter type
     */
    public List<String> declaredSecrets(PipelineDefinition definition) {
        return checks.credentialNames(definition, trackerCredentials(definition)).stream()
                .distinct()
                .toList();
    }

    /**
     * The child-environment allowlist an agent round of {@code definition} composes — the run
     * assembly's own composition over the secrets port.
     *
     * @throws IllegalArgumentException if a passthrough name is a declared credential
     * @throws UsageException if the {@code tracker} section names an unregistered adapter type
     */
    public ChildEnvAllowlist roundChildEnv(PipelineDefinition definition) {
        return checks.childEnv(sandbox.envPassthrough(), definition, trackerCredentials(definition));
    }

    /**
     * The {@code PATH} a host agent round of {@code definition} hands its child — the value the
     * launch owner resolves the agent CLI on.
     *
     * @return the child's {@code PATH}, or {@code null} when none resolves
     */
    public @Nullable String childPath(PipelineDefinition definition) {
        return HostTaskExecutionEnvironment.childPath(roundChildEnv(definition));
    }

    /**
     * The tracker adapter's own read-only probe of {@code tracker}, relayed by the tracker wiring
     * with its own secrets (FR3, design D7 of add-doctor-command) — never the adapter's {@code
     * create}, so nothing is provisioned. Called only for {@code doctor --online}.
     *
     * @return the adapter's verdict; a failure to obtain any answer propagates as the adapter throws
     * @throws UsageException if {@code tracker} names an unregistered adapter type
     */
    public TrackerReadVerdict probeTracker(TrackerConfig tracker) {
        return trackerWiring.probeRead(tracker);
    }

    /** The tracker adapter's declared credential names; none without a {@code tracker} section. */
    private List<String> trackerCredentials(PipelineDefinition definition) {
        TrackerConfig tracker = definition.tracker();
        return tracker == null
                ? List.of()
                : trackerWiring.resolveFactory(tracker).credentialEnvVars(tracker);
    }
}
