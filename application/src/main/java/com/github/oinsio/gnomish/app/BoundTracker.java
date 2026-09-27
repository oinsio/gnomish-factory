package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.List;

/**
 * The <em>bound tracker</em>: what one {@code take} or {@code serve} invocation has bound once its
 * tracker is provisioned — the startup law, the tracker binding and the live tracker — carried as
 * one value from the command that binds it to the last relay of the dispatch chain (design D8 of
 * collapse-composition-roots). It differs from the {@link TakeOrder}, which additionally holds one
 * fetched task, and from the {@link SlotWiring}, which is the equipment a slot works with.
 *
 * <p><b>Membership rule.</b> A member exists only once the tracker is provisioned and stays fixed
 * for the whole invocation. A value that is known earlier (a property, a path) or that changes
 * within the invocation (a task, a heartbeat) does not belong here.
 *
 * <p><b>Constructs nothing.</b> The record builds no collaborator: its one derived method reads
 * two members, and its one wither is a copy. Anything that builds a component from these members
 * belongs to its consumer ({@link SlotWiringFactory} builds the abort handler over {@link
 * #tracker()}).
 *
 * <p>Implements FR8 of collapse-composition-roots.
 *
 * @param definition the pipeline definition bound from the refreshed default branch
 * @param trustedBase the trusted tier bound once at startup
 * @param trackerConfig the definition's {@code tracker:} section
 * @param factory the adapter factory the tracker type resolved to
 * @param tracker the live tracker every consumer below the command works with — in {@code serve},
 *     the health-wrapped one ({@link #withTracker})
 * @param instanceId this invocation's minted instance id
 */
record BoundTracker(
        PipelineDefinition definition,
        TrustedBaseContext trustedBase,
        TrackerConfig trackerConfig,
        TrackerAdapterFactory factory,
        Tracker tracker,
        InstanceId instanceId) {

    /** The credential variable names the bound adapter declares, scrubbed from agent environments. */
    List<String> credentialEnvVars() {
        return factory.credentialEnvVars(trackerConfig);
    }

    /**
     * This binding over {@code decorated}, every other member unchanged — the serve root's
     * derivation over the health-wrapped tracker (design D8, amendment b), so nothing below it
     * receives the raw one.
     */
    BoundTracker withTracker(Tracker decorated) {
        return new BoundTracker(definition, trustedBase, trackerConfig, factory, decorated, instanceId);
    }
}
