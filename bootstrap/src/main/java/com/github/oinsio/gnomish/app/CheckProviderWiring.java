package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam;
import com.github.oinsio.gnomish.adapter.check.ProviderDispatchingExternalCheckClient;
import com.github.oinsio.gnomish.adapter.console.InteractiveExternalCheckClient;
import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.port.check.ExternalCheckPinContributor;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the operator's configured check providers contribute to one run: the credential names the
 * child-environment allowlist must refuse and scrub, and the pin-guarded {@link
 * ExternalCheckClient} the engine polls (FR5, FR6, FR16, FR17, design D8, D10, D11 of
 * add-plugin-architecture). Both are derived from the same resolved {@code factory.check.<provider>}
 * subsections, which is why they live together and apart from {@link RunAssembler}: the assembler
 * wires executors, judges and consoles, and asks this class for everything a check provider owns.
 *
 * <p>Implements FR16, FR26 of add-sandbox-core; FR5, FR6, FR16, FR17 of add-plugin-architecture.
 */
final class CheckProviderWiring {

    private CheckProviderWiring() {}

    /**
     * The declared credential names the run's {@link ChildEnvAllowlist} refuses in passthrough
     * and scrubs from every composed child environment: the active tracker adapter's (supplied by
     * the caller) unioned with every configured check provider's own SPI declaration (FR17, design
     * D11 of add-plugin-architecture).
     *
     * <p>This used to name {@code GithubCheckClientFactory.TOKEN_ENV_VAR} here, in core. It cannot:
     * once github is a discovered plugin, core has no vendor constant to name, and a credential
     * name supplied as configuration data by a connection profile is invisible to a compile-time
     * constant anyway. So the names come from the providers themselves, and a plugin's credential
     * is scrubbed — and barred from the passthrough allowlist — with no core source naming it.
     *
     * <p>Two declarations are unioned, because credentials reach a provider two ways: from its
     * configured connection subsection, and — for the built-in {@code http} provider, which serves
     * arbitrary endpoints — from each check's own manifest params (FR11). A manifest-named
     * credential is therefore scrubbed and refused in passthrough exactly like a configured one.
     */
    static List<String> credentialNames(
            ManualRunAssembly assembly, PipelineDefinition definition, List<String> credentialEnvVarsToScrub) {
        var names = new ArrayList<>(credentialEnvVarsToScrub);
        names.addAll(CheckProviderSeam.credentialEnvVars(checkSubsections(assembly), assembly.checkClientRegistry));
        names.addAll(CheckProviderSeam.checkCredentialEnvVars(definition, assembly.checkClientRegistry));
        return names;
    }

    /**
     * The operator's {@code factory.check.<provider>} subsections with every {@code connection:
     * <name>} reference resolved against {@code factory.connections} (FR16, design D8 of
     * add-plugin-architecture), so a provider is built — and asked for its credential names — over
     * the same inline-shaped connection data whether the operator inlined it or shared a profile
     * between the ports one vendor serves.
     */
    private static Map<String, Map<String, Object>> checkSubsections(ManualRunAssembly assembly) {
        return CheckProviderSeam.resolve(
                assembly.factoryProperties.check(), ConnectionProfiles.of(assembly.factoryProperties.connections()));
    }

    /**
     * Selects and pin-guards the run's {@link ExternalCheckClient} (task 8.4 of add-sandbox-core;
     * FR5, FR6, design D10 of add-plugin-architecture): with any {@code factory.check.<provider>}
     * subsection configured, a {@link ProviderDispatchingExternalCheckClient} over the discovered
     * {@code registry} — each check routed to its provider's client, built lazily on first
     * selection so a dormant provider resolves no credential; otherwise the interactive console
     * client, which contributes no pin paths.
     *
     * <p>The engine port is unchanged either way: the composite <em>is</em> an {@code
     * ExternalCheckClient}, so per-check provider selection stays wiring rather than engine
     * semantics. Either client is pin-guarded by {@code runLaw} (FR16, D10; D12 of
     * add-base-ref-resolution) against the very commit the law was frozen from. The pin
     * contribution dispatches per provider too, so the guard unions the selected provider's paths
     * exactly as the single-provider wiring did.
     *
     * <p>Package-private testing seam: specs call {@link ManualRunAssembly#externalCheckClient}
     * with a hand-built registry over a fake secrets provider.
     */
    static ExternalCheckClient externalCheckClient(
            ManualRunAssembly assembly,
            DialogConsole console,
            RunLaw runLaw,
            Map<String, CheckClientFactory> registry,
            CheckRunContext runContext) {
        var configured = checkSubsections(assembly);
        ExternalCheckClient client;
        ExternalCheckPinContributor contributor;
        if (configured.isEmpty()) {
            client = new InteractiveExternalCheckClient(console);
            contributor = ExternalCheckPinContributor.none();
        } else {
            var dispatching = new ProviderDispatchingExternalCheckClient(
                    registry, configured, assembly.secretsProvider, runContext);
            client = dispatching;
            contributor = dispatching.pinContributor();
        }
        return runLaw.pinGuarded(client, contributor);
    }
}
