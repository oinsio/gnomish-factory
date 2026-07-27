/**
 * The in-memory reference {@link com.github.oinsio.gnomish.app.port.tracker.Tracker}
 * adapter (design D15): {@link com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker},
 * its test-support companion {@link
 * com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness}
 * (human operations, fixture seeding, race-interleaving hook), the
 * package-private task-store holder {@link
 * com.github.oinsio.gnomish.adapter.tracker.inmemory.TrackedTask}, and {@link
 * com.github.oinsio.gnomish.adapter.tracker.inmemory.NoSuchTrackedTaskException}.
 *
 * <p>Serves as the executable example for third-party adapter authors (FR3,
 * G2): a complete, thread-safe implementation of the full {@code Tracker}
 * port with no configuration subsection, plus the reference shape for an
 * adapter's own test harness. Task 2.7 wires the shared port contract spec
 * suite against it.
 *
 * <p>Implements FR1, FR3 of add-tracker-port.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import org.jspecify.annotations.NullMarked;
