package com.github.oinsio.gnomish.app.serve;

import java.util.Set;

/**
 * The role a {@link WorktreeJanitor} needs from its branch pass: one pass over the clone's task
 * branches, run on every tick after the age pass (FR4 of fix-terminal-receipt-convergence,
 * proposal Q1). A seam a spec fakes is a role interface in the owning module (clause (iii) of
 * design D22 of supervise-daemon-loops-and-embed-dashboard); {@link DeliveredBranchPass} is the
 * production implementation.
 *
 * <p>Implements FR4 of fix-terminal-receipt-convergence.
 */
@FunctionalInterface
public interface BranchPass {

    /**
     * Runs one pass over the clone's task branches.
     *
     * @param heldKeys the sanitized ids of the tasks occupying a slot of this instance, which the
     *     pass leaves to their slots; never null
     */
    void run(Set<String> heldKeys);
}
