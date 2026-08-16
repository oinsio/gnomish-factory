package com.github.oinsio.gnomish.app.port.tracker;

/**
 * The outcome of a {@code claim} attempt (design D1 sketch: {@code Acquired |
 * Held(otherInstance)}): {@link Acquired} — the caller now holds the claim;
 * {@link Held} — another instance already holds it, named by {@code
 * otherInstance}. Every adapter's claim implementation SHALL be observably
 * atomic: in a concurrent race exactly one caller receives {@link Acquired}
 * (NFR-R1).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR1 of add-tracker-port.
 */
public sealed interface ClaimResult permits ClaimResult.Acquired, ClaimResult.Held {

    /** The caller's claim succeeded; the task transitions to {@code Working}. */
    record Acquired() implements ClaimResult {}

    /**
     * The claim was lost to {@code otherInstance}, which already holds the task.
     *
     * <p>{@code otherInstance} is a plain {@link String}: the port carries the
     * flattened {@link InstanceId#value()} form ({@code <name>-<suffix>}), not the
     * composite {@link InstanceId} type. The id is only an informational label
     * here — a lost claim needs to name the holder for a message (FR9, UX2) — so
     * the port stays agnostic to the composite's structure.
     *
     * @param otherInstance the identifier of the instance holding the claim; never blank
     */
    record Held(String otherInstance) implements ClaimResult {

        public Held {
            otherInstance = requireNonBlank(otherInstance);
        }

        /**
         * Fails fast on a blank {@code otherInstance}: a lost claim must name the
         * holder so the caller can refuse with a useful message (FR9, UX2). Kept as
         * an explicit static method rather than inline in the compact constructor:
         * PIT's record filter suppresses all mutations inside a record's canonical
         * constructor, which would silently exempt this validation from the 100%
         * mutation gate.
         */
        private static String requireNonBlank(String value) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("ClaimResult.Held.otherInstance must not be blank");
            }
            return value;
        }
    }
}
