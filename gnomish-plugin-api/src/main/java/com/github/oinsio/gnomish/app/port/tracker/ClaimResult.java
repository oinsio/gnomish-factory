package com.github.oinsio.gnomish.app.port.tracker;

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;

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
 * <p>Implements FR1 of add-tracker-port. Implements FR13 of
 * harden-task-branch-contract ({@link Acquired#epoch()}).
 */
public sealed interface ClaimResult permits ClaimResult.Acquired, ClaimResult.Held {

    /**
     * The caller's claim succeeded; the task transitions to {@code Working}.
     *
     * <p>The claim carries the tenure's {@link ClaimEpoch}: the opaque, strictly
     * increasing token this (re)claim was issued, which the holder stamps into
     * every commit and tracker write it makes until the tenure ends (FR13 of
     * harden-task-branch-contract). Each adapter picks its own monotonic source
     * — the GitHub adapter uses the tracker-assigned claim comment id — and core
     * only ever compares two epochs for order, never interprets one.
     *
     * @param epoch the epoch issued with this claim; never null
     */
    record Acquired(ClaimEpoch epoch) implements ClaimResult {}

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
