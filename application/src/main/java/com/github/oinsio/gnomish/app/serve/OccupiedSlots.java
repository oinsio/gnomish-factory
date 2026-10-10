package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.Set;

/**
 * The role a {@link WorktreeJanitor} needs from the daemon's slots: the tasks occupying one right
 * now, whose environments are never disposed regardless of age (design D10 of add-factory-serve). A
 * seam a spec fakes is a role interface in the owning module, never a JDK functional type (clause
 * (iii) of design D22 of supervise-daemon-loops-and-embed-dashboard) — this replaces a {@code
 * Supplier<Set<TaskRef>>}. {@link SlotLedger} is the production implementation.
 *
 * <p>Implements FR14 of add-factory-serve; FR18 of supervise-daemon-loops-and-embed-dashboard.
 */
@FunctionalInterface
public interface OccupiedSlots {

    /**
     * A snapshot of the tasks occupying a slot of this instance, read fresh on every janitor tick.
     *
     * @return the occupied refs; never null
     */
    Set<TaskRef> occupiedRefs();
}
