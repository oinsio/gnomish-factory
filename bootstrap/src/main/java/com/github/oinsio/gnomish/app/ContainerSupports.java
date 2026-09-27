package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry;
import com.github.oinsio.gnomish.sandbox.BindingProperties;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.environment.DockerRuntimeProbe;
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The <em>container supports</em>: the installation's container-mode equipment — the check
 * providers, the factory, sandbox and binding settings, the discovered adapter bindings, the Docker
 * probe and the process's tenure record — and the constructions made from it (design D10 of
 * collapse-composition-roots). {@code gnomish run} and {@code take}/{@code serve} build the same
 * container support, differing only in the ownership label it stamps: {@link #manualSupport()}
 * and {@link #takeSupport()}. {@link #plan} resolves a manual run's execution mode against the
 * same settings.
 *
 * <p>The tenure record is read from {@link TaskGit#epochs()} and nowhere else (FR4 of
 * fix-claim-epoch-fence), so a container run stamps from the book its claim fills.
 *
 * <p>Implements FR1, FR2 of add-serve-sandbox-lifecycle; D10 of collapse-composition-roots.
 */
@Component
public final class ContainerSupports {

    private final Map<String, CheckClientFactory> checkClientRegistry;
    private final FactoryProperties factoryProperties;
    private final SandboxProperties sandboxProperties;
    private final BindingProperties bindingProperties;
    private final AdapterBindingRegistry bindingRegistry;
    private final ClaimEpochSource epochs;
    private final BooleanSupplier dockerProbe;

    /** Production wiring: the real Docker probe. */
    @Autowired
    ContainerSupports(
            Map<String, CheckClientFactory> checkClientRegistry,
            FactoryProperties factoryProperties,
            SandboxProperties sandboxProperties,
            BindingProperties bindingProperties,
            AdapterBindingRegistry bindingRegistry,
            TaskGit git) {
        this(
                checkClientRegistry,
                factoryProperties,
                sandboxProperties,
                bindingProperties,
                bindingRegistry,
                git,
                DockerRuntimeProbe::dockerAvailable);
    }

    /**
     * The test constructor: a daemon-free spec scripts the container prerequisite probe (D13 of
     * add-sandbox-core) instead of reaching a Docker daemon.
     */
    ContainerSupports(
            Map<String, CheckClientFactory> checkClientRegistry,
            FactoryProperties factoryProperties,
            SandboxProperties sandboxProperties,
            BindingProperties bindingProperties,
            AdapterBindingRegistry bindingRegistry,
            TaskGit git,
            BooleanSupplier dockerProbe) {
        this.checkClientRegistry = checkClientRegistry;
        this.factoryProperties = factoryProperties;
        this.sandboxProperties = sandboxProperties;
        this.bindingProperties = bindingProperties;
        this.bindingRegistry = bindingRegistry;
        this.epochs = git.epochs();
        this.dockerProbe = dockerProbe;
    }

    /** {@code gnomish run}'s container support, stamping {@code manual} (FR2 of add-serve-sandbox-lifecycle). */
    ContainerSupportFactory manualSupport() {
        return supportFactory(OwnershipMode.MANUAL);
    }

    /**
     * {@code take}/{@code serve}'s container dispatch bundle, whose support stamps {@code tracked}
     * — take/serve claim tasks through the tracker, run never does (FR1, FR2 of
     * add-serve-sandbox-lifecycle).
     */
    ContainerTakeSupport takeSupport() {
        return new ContainerTakeSupport(
                factoryProperties,
                bindingProperties,
                sandboxProperties,
                bindingRegistry,
                dockerProbe,
                supportFactory(OwnershipMode.TRACKED));
    }

    /**
     * A manual run's execution plan: the bindings resolve fail-closed (FR14, D13 of
     * add-sandbox-core — container by default, never a silent host fallback) over this
     * installation's settings and probe.
     */
    SandboxModeSelector.Plan plan(PipelineDefinition definition) {
        return SandboxModeSelector.plan(definition, bindingProperties, sandboxProperties, bindingRegistry, dockerProbe);
    }

    /**
     * The support factory closing over {@code mode}, over the check providers' own credential
     * declarations (FR17, design D11 of add-plugin-architecture): a profile-resolved credential
     * name reaches the container's scrub set exactly as an inline one does (FR16, FR17, design
     * D8/D11), since the subsections are resolved against {@code factory.connections} before the
     * providers are asked what they name.
     */
    private ContainerSupportFactory supportFactory(OwnershipMode mode) {
        List<String> checkCredentials = CheckProviderSeam.credentialEnvVars(
                CheckProviderSeam.resolve(
                        factoryProperties.check(), ConnectionProfiles.of(factoryProperties.connections())),
                checkClientRegistry);
        return new ContainerRunSupportFactory(checkCredentials, checkClientRegistry, mode, epochs);
    }
}
