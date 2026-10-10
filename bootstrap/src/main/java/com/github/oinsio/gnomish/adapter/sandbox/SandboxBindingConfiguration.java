package com.github.oinsio.gnomish.adapter.sandbox;

import com.github.oinsio.gnomish.adapter.plugin.ProviderDiscoveryReport;
import com.github.oinsio.gnomish.app.SandboxModeSelector;
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe;
import com.github.oinsio.gnomish.sandbox.AdapterBinding;
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry;
import com.github.oinsio.gnomish.sandbox.BindingProperties;
import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.environment.DockerRuntimeProbe;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the composition root's {@link AdapterBindingRegistry}: the bindings the classpath
 * contributed, discovered once and reported before any stage runs (design D1, D6 of
 * open-adapter-binding-registry).
 *
 * <p>Built once rather than per invocation, unlike the tracker factory: the binding set is
 * process-static — it is fixed by the classpath — so re-resolving it would buy nothing. Every
 * refusal it can raise (an untrusted id, a passport mismatch, a duplicate config name) therefore
 * lands at context refresh, and the remaining one (an unknown configured name) at binding
 * resolution — both before a stage runs, which is what "startup" means throughout this change.
 *
 * <p>Reported through the same {@link ProviderDiscoveryReport} as the tracker and check ports
 * (NFR-O1, UX3) — in its origin-explicit form, since a binding is a value the trust table minted and
 * the class an operator must see is the provider that declared it — with the passport summary as the
 * per-entry detail: for the sandbox the passport is
 * the security-relevant fact, and observability is the compensating control for a trust boundary
 * with no runtime enforcement behind it.
 *
 * <p>Reaches the context as an {@code @AutoConfiguration} listed in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, not by
 * component scan — the composition root scans only {@code com.github.oinsio.gnomish.app} (design
 * D3 of add-tracker-port).
 *
 * <p>Beside the registry it supplies the two other inputs of the execution-mode decision that are
 * fixed for the process: the container-runtime probe — the one place the Docker adapter's probe is
 * named — and the {@link SandboxModeSelector} built once over the registry, the bindings, the
 * sandbox config and that probe (design D22 of supervise-daemon-loops-and-embed-dashboard).
 *
 * <p>Implements FR1, NFR-O1 of open-adapter-binding-registry; UX3 of open-adapter-binding-registry;
 * FR18 of supervise-daemon-loops-and-embed-dashboard.
 */
@AutoConfiguration
public class SandboxBindingConfiguration {

    /** The port name the startup discovery report is written under. */
    private static final String PORT = "sandbox binding";

    /**
     * The discovered bindings, reported with the artifact and passport behind each entry.
     *
     * @return the frozen registry; never null
     */
    @Bean
    public AdapterBindingRegistry adapterBindingRegistry() {
        AdapterBindingRegistry registry = SandboxBindingDiscovery.discover();
        ProviderDiscoveryReport.reportOrigins(
                PORT, registry.providerTypes(), name -> summarize(registry.require(name)));
        return registry;
    }

    /**
     * The container-runtime prerequisite probe (D13 of add-sandbox-core): the Docker adapter's
     * probe, named here and nowhere else in the application's wiring.
     *
     * @return the probe; never null
     */
    @Bean
    public ContainerRuntimeProbe containerRuntimeProbe() {
        return DockerRuntimeProbe::dockerAvailable;
    }

    /**
     * The process's one execution-mode selector, asked by manual runs and by take and serve alike
     * (design D22 of supervise-daemon-loops-and-embed-dashboard).
     *
     * @param bindings the operator's per-stage bindings; never null
     * @param sandbox the operator sandbox config; never null
     * @param registry the discovered bindings; never null
     * @param runtimeProbe the container-runtime probe; never null
     * @return the selector; never null
     */
    @Bean
    public SandboxModeSelector sandboxModeSelector(
            BindingProperties bindings,
            SandboxProperties sandbox,
            AdapterBindingRegistry registry,
            ContainerRuntimeProbe runtimeProbe) {
        return new SandboxModeSelector(bindings, sandbox, registry, runtimeProbe);
    }

    /**
     * The passport summary an operator reads in the startup report: the isolation level plus the
     * three boolean dimensions, so "which binding weakens what" is answerable without running a
     * stage.
     *
     * @param binding the discovered binding; never null
     * @return the summary; never null
     */
    static String summarize(AdapterBinding binding) {
        CapabilityPassport passport = binding.passport();
        return "isolation=" + passport.isolation() + " egress-controlled=" + passport.egressControlled()
                + " task-to-task-boundary=" + passport.taskToTaskBoundary() + " docker-inside="
                + passport.dockerInside();
    }
}
