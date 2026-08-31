package com.github.oinsio.gnomish.app.port.tracker;

import java.time.Instant;

/**
 * The write-side payload for {@code recordAbort}: the marker an adapter
 * persists to make an infrastructure abort reconstructable by any instance
 * (design D1 sketch, FR14) — free-text {@code cause}, the aborting instance's
 * identifier, and {@code at}, the time the abort happened.
 *
 * <p>Deliberately distinct from {@link AbortFacts}: that type is the read-side
 * aggregate ({@code count}, {@code lastAbortAt}) returned by {@code fetchTask}
 * and {@code listReady}, derived by the adapter from the full marker history.
 * {@code AbortRecord} is the single new marker being appended, carrying detail
 * ({@code cause}, {@code instance}) that the aggregate does not need to expose.
 * Reusing {@code AbortFacts} for both directions would either drop this detail
 * on write or force the read-side aggregate to fake a per-abort cause/instance
 * it does not track.
 *
 * <p>{@code instance} is a plain {@link String}: the port carries the flattened
 * {@link InstanceId#value()} form ({@code <name>-<suffix>}), not the composite
 * {@link InstanceId} type — an abort marker only needs an attributable label
 * (FR14), so the port stays agnostic to the composite's structure.
 *
 * <p>{@code category} places the marker in the unified recovery accounting (FR14 of
 * harden-task-branch-contract): a run that died is an {@link RecoveryCause#INSTANCE_CRASH}, a
 * repair of a non-clean branch shape that failed is a {@link RecoveryCause#RECOVERY_FAILURE}. Both
 * spend from the same counter; the category is what lets the quarantine report tell an operator
 * which kind of failure keeps happening.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR14 of add-tracker-port; the category is FR14 of harden-task-branch-contract.
 *
 * @param cause free-text description of what went wrong; never blank
 * @param instance the identifier of the instance recording the abort; never blank
 * @param at when the abort happened; never null
 * @param category which category of the unified accounting this attempt spends; never null
 */
public record AbortRecord(String cause, String instance, Instant at, RecoveryCause category) {

    public AbortRecord {
        cause = requireNonBlank(cause, "cause");
        instance = requireNonBlank(instance, "instance");
    }

    /**
     * The pre-categorization shape: an attempt recorded with no category stated is the category
     * every such marker meant, {@link RecoveryCause#INSTANCE_CRASH}.
     *
     * @param cause free-text description of what went wrong; never blank
     * @param instance the identifier of the instance recording the abort; never blank
     * @param at when the abort happened; never null
     */
    public AbortRecord(String cause, String instance, Instant at) {
        this(cause, instance, at, RecoveryCause.INSTANCE_CRASH);
    }

    /**
     * Fails fast on a blank {@code cause}/{@code instance}: an abort marker with no
     * explanation or no attributable instance cannot be reconstructed usefully by
     * another instance (FR14). Kept as an explicit static method rather than inline
     * in the compact constructor: PIT's record filter suppresses all mutations
     * inside a record's canonical constructor, which would silently exempt this
     * validation from the 100% mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("AbortRecord." + component + " must not be blank");
        }
        return value;
    }
}
