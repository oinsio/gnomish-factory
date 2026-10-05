package com.github.oinsio.gnomish.build

import groovy.transform.Immutable

/**
 * What the mutation gate mutates in this build — the one value every PIT consumer reads
 * (D1 of scope-pit-locally). Obtained once per build by {@link MutationScopeSource}; the module
 * scripts derive {@code targetClasses} and the shared skip from it, and compute nothing of their
 * own.
 *
 * <p>Three modes (D5): {@code Mode#BRANCH} — the classes the branch changed against its scope
 * base, per module, with any module widened to its whole tree by a test change (D4);
 * {@code Mode#ALL} — every module's whole tree, by request or because no base could be resolved
 * ({@link #reason} says which, NFR-R1); {@code Mode#EXPLICIT} — today's comma-separated glob list,
 * intersected by each module with what it owns.
 *
 * <p>Value-typed with generated {@code equals}/{@code hashCode} and {@code Serializable}, as
 * {@link HardwareSpec.Machine} is: the configuration cache records the obtained value and compares
 * each build's freshly obtained one against it, so a scope that moved invalidates the cache
 * (NFR-R2).
 *
 * <p>Implements FR1, FR2, FR3, FR4 of scope-pit-locally.
 */
@Immutable
class MutationScope implements Serializable {

    enum Mode { BRANCH, ALL, EXPLICIT }

    Mode mode
    /** The scope base commit (full SHA); {@link Mode#BRANCH} only. */
    String base
    /** How the base was chosen, e.g. {@code merge-base main} or {@code first parent of HEAD}. */
    String baseOrigin
    /** Why the whole tree is mutated; {@link Mode#ALL} only. */
    String reason
    /** The requested globs; {@link Mode#EXPLICIT} only. */
    List<String> globs
    /** Module project path → changed production class names; {@link Mode#BRANCH} only. */
    Map<String, Set<String>> changedClasses
    /** Module project path → why its whole production tree is mutated (D4); {@link Mode#BRANCH} only. */
    Map<String, String> widened

    static MutationScope all(String reason) {
        Map<String, Set<String>> noClasses = [:]
        Map<String, String> noWidened = [:]
        new MutationScope(mode: Mode.ALL, reason: reason, globs: [], changedClasses: noClasses, widened: noWidened)
    }

    static MutationScope explicit(List<String> globs) {
        Map<String, Set<String>> noClasses = [:]
        Map<String, String> noWidened = [:]
        new MutationScope(mode: Mode.EXPLICIT, globs: globs, changedClasses: noClasses, widened: noWidened)
    }

    static MutationScope branch(String base, String baseOrigin, ChangedSources changes) {
        new MutationScope(mode: Mode.BRANCH, base: base, baseOrigin: baseOrigin, globs: [],
                changedClasses: changes.classes, widened: changes.widened)
    }
}
