package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.app.CheckClientFactory;
import com.github.oinsio.gnomish.app.CheckRunContext;
import com.github.oinsio.gnomish.app.port.check.ExternalCheckPinContributor;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes each {@code external} check to the client of the provider it selects, behind the engine's
 * single unchanged {@link ExternalCheckClient} port (FR3, FR6, design D10 of
 * add-plugin-architecture). Per-check provider selection is wiring, not engine semantics: the
 * engine keeps handing one port a {@link VerifyCheck.External} and reading back one {@link
 * PollStatus}, and this composite decides whose client answers.
 *
 * <p>The selection is read straight off the check's own {@code provider} field, which the loader
 * records for every {@code external} check — declared, or defaulted to {@code github} for a manifest
 * written before providers existed (FR13). Nothing is guessed here: a check reaching this class
 * naming a provider outside the registry has already passed a load seam that grades exactly that,
 * so the failure below is a wiring invariant, not a user-facing validation.
 *
 * <p>Clients are constructed lazily and memoized per provider, so a configured but never-selected
 * provider is never built — no credential of a dormant provider is resolved and no connection to it
 * is attempted (FR3).
 *
 * <p>The pin contribution dispatches the same way ({@link #pinContributor()}), so the guard that
 * wraps this seam unions the *selected* provider's contributed paths with the law-declared ones —
 * exactly what a single-provider wiring did before, now per check (FR15).
 *
 * <p>Implements FR3, FR5, FR6, FR15 of add-plugin-architecture.
 */
public final class ProviderDispatchingExternalCheckClient implements ExternalCheckClient {

    private final Map<String, CheckClientFactory> registry;
    private final Map<String, Map<String, Object>> configured;
    private final SecretsProvider secrets;
    private final CheckRunContext runContext;
    private final Map<String, ExternalCheckClient> clients = new ConcurrentHashMap<>();

    /**
     * A composite for a run that supplies no interpolation values — the hand-assembled case; a
     * provider that interpolates then fails its check closed (NFR-S2).
     *
     * @param registry the discovered check providers keyed by discriminator; never null
     * @param configured the {@code factory.check} subsections keyed by provider; never null
     * @param secrets the seam each provider resolves its own credentials through; never null
     */
    public ProviderDispatchingExternalCheckClient(
            Map<String, CheckClientFactory> registry,
            Map<String, Map<String, Object>> configured,
            SecretsProvider secrets) {
        this(registry, configured, secrets, CheckRunContext.none());
    }

    /**
     * @param registry the discovered check providers keyed by discriminator; never null
     * @param configured the {@code factory.check} subsections keyed by provider; never null
     * @param secrets the seam each provider resolves its own credentials through; never null
     * @param runContext the run's whitelisted interpolation values, handed to each provider at
     *     construction (NFR-S2, design D5); never null
     */
    public ProviderDispatchingExternalCheckClient(
            Map<String, CheckClientFactory> registry,
            Map<String, Map<String, Object>> configured,
            SecretsProvider secrets,
            CheckRunContext runContext) {
        this.registry = Map.copyOf(registry);
        this.configured = Map.copyOf(configured);
        this.secrets = secrets;
        this.runContext = runContext;
    }

    /** The pin contribution of whichever provider each check selects (FR15). */
    public ExternalCheckPinContributor pinContributor() {
        return check -> factoryFor(check).pinContributor().pinPaths(check);
    }

    @Override
    public PollStatus poll(VerifyCheck.External check, Workspace workspace) {
        return clientFor(check).poll(check, workspace);
    }

    /** The memoized client of the check's provider, constructed on first selection (FR3). */
    private ExternalCheckClient clientFor(VerifyCheck.External check) {
        String provider = check.provider();
        return clients.computeIfAbsent(
                provider, p -> factoryFor(check).create(secrets, configured.getOrDefault(p, Map.of()), runContext));
    }

    /**
     * The factory of the check's provider, or a failure naming the provider and the discovered set
     * — a manifest can only reach this by naming a provider no jar serves.
     */
    private CheckClientFactory factoryFor(VerifyCheck.External check) {
        String provider = check.provider();
        CheckClientFactory factory = registry.get(provider);
        if (factory == null) {
            throw new IllegalStateException("check '%s' selects check provider '%s', which no discovered jar serves; "
                            .formatted(check.checkId(), provider)
                    + "discovered providers: " + registry.keySet());
        }
        return factory;
    }
}
