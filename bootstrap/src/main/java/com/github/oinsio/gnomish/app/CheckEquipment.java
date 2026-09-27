package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam;
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ProviderDispatchingExternalCheckClient;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.console.InteractiveExternalCheckClient;
import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.port.check.ExternalCheckPinContributor;
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The check equipment one installation runs every stage's checks with (design D11 and the {@code
 * CheckEquipment} row of collapse-composition-roots): the two built-in check runners, the
 * discovered check-client registry and the credential seam, over the operator's {@code
 * factory.check.<provider>} configuration. {@link RunAssembler} asks it for a run's three check
 * ports and for the credential names those ports' providers declare; nothing reads a member back
 * out.
 *
 * <p>The {@link SecretsProvider} is a member with no accessor (NFR-S1): a holder of the equipment
 * can have a provider resolve a credential on first poll, but cannot obtain the seam or hand it on.
 * The {@link FactoryProperties} it holds are read for the check subsections and the connection
 * profiles only; the executor and judge settings the assembly reads from the same bean are no
 * concern of this class.
 *
 * <p>Implements FR16, FR26 of add-sandbox-core; FR5, FR6, FR16, FR17 of add-plugin-architecture;
 * FR1, FR4, NFR-S1 of collapse-composition-roots.
 */
@NullMarked
public final class CheckEquipment {

    private final FilesExistCheckRunner filesExistCheckRunner;
    private final ShellCommandCheckRunner shellCommandCheckRunner;
    private final Map<String, CheckClientFactory> checkClientRegistry;
    private final SecretsProvider secretsProvider;
    private final FactoryProperties factoryProperties;

    CheckEquipment(
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            Map<String, CheckClientFactory> checkClientRegistry,
            SecretsProvider secretsProvider,
            FactoryProperties factoryProperties) {
        this.filesExistCheckRunner = filesExistCheckRunner;
        this.shellCommandCheckRunner = shellCommandCheckRunner;
        this.checkClientRegistry = checkClientRegistry;
        this.secretsProvider = secretsProvider;
        this.factoryProperties = factoryProperties;
    }

    /**
     * The builtin-check runner for one run: reading the attempt commit through the sandbox pieces
     * in container mode, the working tree on the host.
     */
    FilesExistCheckRunner builtinRunner(@Nullable SandboxRunPieces sandbox) {
        return sandbox == null
                ? filesExistCheckRunner
                : filesExistCheckRunner.withAttemptReader(sandbox.attemptReader());
    }

    /**
     * The command-check runner for one run: composing child environments from {@code childEnv},
     * and running each check in the sandbox pieces' check environments in container mode.
     */
    ShellCommandCheckRunner commandRunner(ChildEnvAllowlist childEnv, @Nullable SandboxRunPieces sandbox) {
        var runner = shellCommandCheckRunner.withChildEnv(childEnv);
        return sandbox == null ? runner : runner.withEnvironments(sandbox.checkEnvironments());
    }

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
    List<String> credentialNames(PipelineDefinition definition, List<String> credentialEnvVarsToScrub) {
        var names = new ArrayList<>(credentialEnvVarsToScrub);
        names.addAll(CheckProviderSeam.credentialEnvVars(checkSubsections(), checkClientRegistry));
        names.addAll(CheckProviderSeam.checkCredentialEnvVars(definition, checkClientRegistry));
        return names;
    }

    /**
     * Selects and pin-guards the run's {@link ExternalCheckClient} over the discovered registry;
     * see {@link #externalCheckClient(DialogConsole, RunLaw, CheckRunContext, Map)}.
     */
    ExternalCheckClient externalCheckClient(DialogConsole console, RunLaw runLaw, CheckRunContext runContext) {
        return externalCheckClient(console, runLaw, runContext, checkClientRegistry);
    }

    /**
     * Selects and pin-guards the run's {@link ExternalCheckClient} (task 8.4 of add-sandbox-core;
     * FR5, FR6, design D10 of add-plugin-architecture): with any {@code factory.check.<provider>}
     * subsection configured, a {@link ProviderDispatchingExternalCheckClient} over {@code registry}
     * — each check routed to its provider's client, built lazily on first selection so a dormant
     * provider resolves no credential; otherwise the interactive console client, which contributes
     * no pin paths.
     *
     * <p>The engine port is unchanged either way: the composite <em>is</em> an {@code
     * ExternalCheckClient}, so per-check provider selection stays wiring rather than engine
     * semantics. Either client is pin-guarded by {@code runLaw} (FR16, D10; D12 of
     * add-base-ref-resolution) against the very commit the law was frozen from. The pin
     * contribution dispatches per provider too, so the guard unions the selected provider's paths
     * exactly as the single-provider wiring did.
     *
     * <p>The {@code registry} parameter is the package-private testing seam: specs reach it through
     * {@link ManualRunAssembly#externalCheckClient} with a hand-built registry.
     */
    ExternalCheckClient externalCheckClient(
            DialogConsole console,
            RunLaw runLaw,
            CheckRunContext runContext,
            Map<String, CheckClientFactory> registry) {
        var configured = checkSubsections();
        ExternalCheckClient client;
        ExternalCheckPinContributor contributor;
        if (configured.isEmpty()) {
            client = new InteractiveExternalCheckClient(console);
            contributor = ExternalCheckPinContributor.none();
        } else {
            var dispatching =
                    new ProviderDispatchingExternalCheckClient(registry, configured, secretsProvider, runContext);
            client = dispatching;
            contributor = dispatching.pinContributor();
        }
        return runLaw.pinGuarded(client, contributor);
    }

    /**
     * The operator's {@code factory.check.<provider>} subsections with every {@code connection:
     * <name>} reference resolved against {@code factory.connections} (FR16, design D8 of
     * add-plugin-architecture), so a provider is built — and asked for its credential names — over
     * the same inline-shaped connection data whether the operator inlined it or shared a profile
     * between the ports one vendor serves.
     */
    private Map<String, Map<String, Object>> checkSubsections() {
        return CheckProviderSeam.resolve(
                factoryProperties.check(), ConnectionProfiles.of(factoryProperties.connections()));
    }
}
