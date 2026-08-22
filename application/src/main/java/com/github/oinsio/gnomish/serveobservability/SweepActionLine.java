package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A ledger {@code sweepAction} line: one stop or dispose the sandbox-lifecycle sweep actually
 * performed (NFR-O2 of add-serve-sandbox-lifecycle). Only the three acting categories reach the
 * ledger as a line of their own — {@code checked-alive}, {@code kept-under-threshold} and {@code
 * skipped-no-verdict} objects are never itemized, they are counted in the tick's {@link
 * SweepTickLine} — so a day of quiet ticks costs one line per tick, not one per container on the
 * host.
 *
 * <p>{@link SweepVerdictCategory} is reused verbatim rather than mirrored by a wire-facing twin,
 * exactly as {@link TaskOutcomeLine} reuses {@code ParkReason}: the vocabulary is already the
 * shared one every entry point emits (FR9), and a second copy here would only add a mapping to
 * keep in step.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O2 of add-serve-sandbox-lifecycle.
 *
 * @param instance the writing process's identity; never null
 * @param at when the action was performed; never null
 * @param objectName the acted-on Docker object's own name; never blank
 * @param role the object's lifecycle role, as a short label (e.g. {@code "main-box"}); never blank
 * @param mode the object's ownership mode ({@code "tracked"} | {@code "manual"}); never blank
 * @param taskKey the base task key the object belongs to; never blank
 * @param category the verdict category that licensed the action; never null
 * @param reason a short explanation of the verdict; never blank
 * @param age the object's age at evaluation time, when one was measured; null otherwise
 */
public record SweepActionLine(
        InstanceInfo instance,
        Instant at,
        String objectName,
        String role,
        String mode,
        String taskKey,
        SweepVerdictCategory category,
        String reason,
        @Nullable Duration age)
        implements LedgerLine {

    public SweepActionLine {
        requireNonBlank(objectName, "objectName");
        requireNonBlank(role, "role");
        requireNonBlank(mode, "mode");
        requireNonBlank(taskKey, "taskKey");
        requireNonBlank(reason, "reason");
        requireActing(category);
    }

    /**
     * Kept as a shared static method rather than inline in the compact constructor: PIT's record
     * filter suppresses all mutations inside a record's canonical constructor, which would silently
     * exempt these validations from the 100% mutation gate.
     *
     * <p>{@code @DoNotMutate}: PIT's Gregor engine crashes its minion JVM (RUN_ERROR, not a real
     * coverage gap) on some mutations of this record's own methods via JVMTI RedefineClasses (see
     * {@link com.github.oinsio.gnomish.app.port.git.UsageTotals}'s identical exemption). Fully
     * covered by {@code SweepActionLineSpec}'s "a blank required field is rejected" scenario.
     */
    @DoNotMutate
    private static void requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("SweepActionLine." + component + " must not be blank");
        }
    }

    /**
     * Enforces "untouched objects are never itemized in the ledger" (NFR-O2) in the type itself:
     * a caller that itemizes a checked-alive object fails here rather than quietly flooding a
     * day's ledger with one line per container.
     *
     * <p>{@code @DoNotMutate}: same JVMTI RedefineClasses exemption as {@link #requireNonBlank}
     * above. Fully covered by {@code SweepActionLineSpec}'s "an acting category is accepted"/"an
     * untouched category is rejected" scenarios.
     */
    @DoNotMutate
    private static void requireActing(SweepVerdictCategory category) {
        boolean acting = category == SweepVerdictCategory.STOPPED_ORPHAN
                || category == SweepVerdictCategory.DISPOSED_AGED
                || category == SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE;
        if (!acting) {
            throw new IllegalArgumentException("SweepActionLine.category must be a stop or dispose, was " + category);
        }
    }
}
