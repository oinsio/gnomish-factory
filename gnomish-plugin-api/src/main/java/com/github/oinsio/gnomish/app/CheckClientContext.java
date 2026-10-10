package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import java.util.Map;

/**
 * Everything the host hands a {@link CheckClientFactory} when it asks for a live check client — the
 * one argument of {@link CheckClientFactory#create(CheckClientContext)} (design D21 of
 * supervise-daemon-loops-and-embed-dashboard). The host implements it; a plugin only reads it.
 *
 * <p>The factory is built by {@code ServiceLoader} through a public no-arg constructor, before any
 * collaborator exists, so every host-provided collaborator reaches it here and nowhere else — never
 * in its constructor, never split across overloads.
 *
 * <p><b>Evolution rule.</b> A new host collaborator is a new {@code default} accessor on this
 * interface, never an overload of {@code create}: the factory's single {@code create} signature
 * stays the whole contract, and an implementor cannot override "the wrong link" of a chain and
 * quietly build a collaborator of its own.
 *
 * <p>Implements FR23 of supervise-daemon-loops-and-embed-dashboard.
 */
public interface CheckClientContext {

    /**
     * The seam the provider resolves its named credentials through (NFR-S1).
     *
     * @return the secrets seam; never null
     */
    SecretsProvider secrets();

    /**
     * This provider's validated {@code factory.check.<provider>} operator subsection as raw untyped
     * content, connection profiles already resolved.
     *
     * @return the subsection; never null, possibly empty for a provider that needs no connection
     *     configuration
     */
    Map<String, Object> subsection();

    /**
     * The run's allowlisted interpolation values (NFR-S2, design D5 of add-plugin-architecture),
     * which a provider composing a request from manifest text may substitute into it. A provider
     * whose target is fully determined by its connection and check id ignores it.
     *
     * @return the run context; never null, {@link CheckRunContext#none()} for a run that supplies no
     *     values
     */
    CheckRunContext runContext();

    /**
     * The host's time equipment: every instant the client stamps and every wait it makes reads it,
     * so the plugin runs on the same time as the host — and on virtual time under test (FR20 of
     * supervise-daemon-loops-and-embed-dashboard).
     *
     * @return the time equipment; never null
     */
    TimeEquipment timeEquipment();
}
