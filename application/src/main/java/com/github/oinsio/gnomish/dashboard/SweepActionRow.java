package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One row of the sandbox hygiene section's recent-actions table: a stop or dispose the sweep
 * performed, read back from a ledger {@code sweepAction} line (NFR-O3, UX1 of
 * add-serve-sandbox-lifecycle).
 *
 * <p>{@code mode} is carried because it decides how the row READS, not only how it renders: a
 * {@code manual} age-stop is routine cleanup, a {@code tracked} one means an instance died or hung
 * (UX2), and only the latter raises the incident alert.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O3, UX1, UX2 of add-serve-sandbox-lifecycle.
 *
 * @param at when the action was performed; never null
 * @param objectName the acted-on Docker object's own name; never null
 * @param role the object's lifecycle role; never null
 * @param mode the object's ownership mode ({@code "tracked"} | {@code "manual"}); never null
 * @param taskKey the base task key the object belonged to; never null
 * @param category the verdict category that licensed the action; never null
 * @param reason a short explanation of the verdict; never null
 * @param ageSeconds the object's age when the verdict measured one; null otherwise
 */
public record SweepActionRow(
        Instant at,
        String objectName,
        String role,
        String mode,
        String taskKey,
        SweepVerdictCategory category,
        String reason,
        @Nullable Long ageSeconds) {

    /** The ownership mode whose stopped-orphan actions read as a dead-instance incident (UX2). */
    public static final String TRACKED_MODE = "tracked";

    /**
     * Whether this row is the dead-or-hung-instance symptom UX2 describes, as opposed to a routine
     * manual age-policy stop — which still belongs in the breakdown and this table, just not in an
     * incident alert.
     *
     * <p>{@code @DoNotMutate}: PIT's Gregor engine crashes its minion JVM (RUN_ERROR, not a real
     * coverage gap) on some mutations of this record's own methods via JVMTI RedefineClasses (see
     * {@link com.github.oinsio.gnomish.app.port.git.UsageTotals}'s identical exemption). Fully
     * covered by {@code SweepActionRowSpec}'s "only a tracked stopped-orphan is a dead-instance
     * symptom" scenario.
     *
     * @return true for a {@code tracked} stopped-orphan action
     */
    @DoNotMutate
    public boolean isDeadInstanceSymptom() {
        return category == SweepVerdictCategory.STOPPED_ORPHAN && TRACKED_MODE.equals(mode);
    }
}
