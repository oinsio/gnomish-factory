package com.github.oinsio.gnomish.adapter.agent.program;

import java.util.Set;

/**
 * Git's view of a working copy as {@link WorkingCopyCheck} took it (FR11, NFR-S4 of
 * define-executor-contract): its status entries, or why none could be taken.
 *
 * <p>Implements FR11, NFR-S4 of define-executor-contract.
 */
sealed interface WorkingCopyView {

    /**
     * A view taken.
     *
     * @param entries the status entries, in git's order, as {@link StatusEntries#of} reads them; never
     *     null
     */
    record Taken(Set<String> entries) implements WorkingCopyView {}

    /**
     * No view: git could not be run, failed, or its output could not be read whole.
     *
     * @param why the factory's sentence, any quoted output already through an exit; never blank
     */
    record Unreadable(String why) implements WorkingCopyView {}
}
