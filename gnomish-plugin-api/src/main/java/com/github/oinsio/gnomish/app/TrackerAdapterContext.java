package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;

/**
 * Everything the host hands a {@link TrackerAdapterFactory} when it asks for a live tracker — the
 * one argument of {@link TrackerAdapterFactory#create(TrackerAdapterContext)} (design D21 of
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
public interface TrackerAdapterContext {

    /**
     * The seam the adapter resolves its named credentials through (NFR-S1).
     *
     * @return the secrets seam; never null
     */
    SecretsProvider secrets();

    /**
     * The project's validated {@code tracker} section.
     *
     * @return the tracker configuration; never null
     */
    TrackerConfig config();

    /**
     * This process's minted {@link com.github.oinsio.gnomish.app.port.tracker.InstanceId} value,
     * stamped into structural markers by adapters that need it at construction time (task 5.15 of
     * add-tracker-port).
     *
     * @return the instance id value; never null
     */
    String instanceId();

    /**
     * This instance's tenure record — which claim epoch it holds on a given task right now (FR13 of
     * harden-task-branch-contract). An adapter whose writes are physically non-atomic stamps it on
     * every marker it writes; {@link ClaimEpochSource#NONE} for a caller that never claims.
     *
     * @return the tenure record; never null
     */
    ClaimEpochSource epochs();

    /**
     * The host's time equipment: every instant the adapter stamps and every wait it makes reads it,
     * so the plugin runs on the same time as the host — and on virtual time under test (FR20 of
     * supervise-daemon-loops-and-embed-dashboard).
     *
     * @return the time equipment; never null
     */
    TimeEquipment timeEquipment();
}
