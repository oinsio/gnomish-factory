package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real {@link OpenTaskListingSink}: caches the most recent {@link Reaper#reapOnce} listing
 * (or its failure) so {@link LivenessOracle} can read it without issuing its own {@code
 * listOpen} call (design D1, NFR-C2 of add-serve-sandbox-lifecycle). Thread-safe — the reaper
 * publishes from its own tick thread, the oracle reads from whatever thread evaluates a sweep.
 *
 * <p>Starts {@link Listing.Failed} before the reaper's first tick: fail-closed by construction
 * (NFR-R1) — a sweep evaluated before any listing has ever succeeded gets no verdict, never an
 * empty-and-therefore-everything-is-unowned reading.
 *
 * <p>Implements FR3, NFR-C2, NFR-R1 of add-serve-sandbox-lifecycle.
 */
public final class CachedOpenTaskListing implements OpenTaskListingSink {

    private final AtomicReference<Listing> current = new AtomicReference<>(new Listing.Failed());

    @Override
    public void onListed(List<OpenTask> openTasks) {
        current.set(new Listing.Observed(List.copyOf(openTasks)));
    }

    @Override
    public void onListingFailed() {
        current.set(new Listing.Failed());
    }

    /**
     * The most recently published listing.
     *
     * @return the current listing; never null
     */
    public Listing current() {
        return Objects.requireNonNull(current.get());
    }

    /** The two outcomes a {@code listOpen} tick can leave behind — never conflated (D4). */
    public sealed interface Listing {

        /** A successful listing, published verbatim from the reaper's own {@code listOpen}. */
        record Observed(List<OpenTask> openTasks) implements Listing {}

        /** The most recent tick's {@code listOpen} failed; no listing is available. */
        record Failed() implements Listing {}
    }
}
