package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.app.TrackerAdapterFactory;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;

/**
 * Registers {@link InMemoryTracker} as a real, operator-selectable {@code tracker.type: inmemory}
 * (task 5.15): needs no external config beyond the discriminator itself (no config subsection,
 * no credentials), so it costs nothing to offer as a no-external-dependency option for demos, CI,
 * and local trials of the {@code gnomish take} CLI without a real GitHub repo. {@link
 * InMemoryTracker} itself remains, first and foremost, the adapter-author reference (FR3, G2) —
 * this factory only wires a fresh instance per invocation, matching every other registered
 * adapter's per-invocation construction (design: no tracker is ever a shared Spring {@code @Bean}).
 *
 * <p>A fresh {@link InMemoryTracker} is empty: {@code listReady} returns nothing and {@code
 * fetchTask} finds no task until one is seeded, which only {@link InMemoryTrackerHarness} (a
 * test-only companion) can do. This is an accepted, documented limitation of registering
 * {@code inmemory} in production: it is useful for exercising {@code take}'s CLI plumbing and
 * exit-code mapping against an empty or contract-shaped tracker, not for a real standalone task
 * queue with no other seeding mechanism.
 *
 * <p>Short refs have no meaning for this adapter (no canonical id scheme, no {@code repo}
 * config): {@link #expandRef} always throws, matching the port's stance that this adapter exists
 * for reference/contract purposes, not for explicit-mode {@code take <ref>} against short refs.
 *
 * <p>Discovered through {@code ServiceLoader} like any other provider (FR1 of
 * add-plugin-architecture): it declares no credentials and grades no subsection, so it inherits both
 * SPI defaults and needs the {@link SecretsProvider} for nothing.
 *
 * <p>Implements FR1, FR3 of add-tracker-port; FR1, FR2 of add-plugin-architecture.
 */
public final class InMemoryTrackerAdapterFactory implements TrackerAdapterFactory {

    /** {@code tracker.type: inmemory} — this adapter's discovery discriminator (FR1). */
    public static final String TYPE = "inmemory";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
        return new InMemoryTracker();
    }

    @Override
    public TaskRef expandRef(TrackerConfig config, String rawRef) {
        throw new UnsupportedOperationException(
                "the in-memory tracker adapter has no short-ref expansion scheme; use a full TaskRef id");
    }
}
