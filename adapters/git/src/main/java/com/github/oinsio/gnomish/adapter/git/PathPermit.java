package com.github.oinsio.gnomish.adapter.git;

import java.util.Set;

/**
 * Which paths under the state directory a judged commit may change (design D18 of
 * tighten-spec-contract): a gnome commit of a round only its round's decision request, the factory's
 * own in-box closing commit exactly the files the factory wrote into it, a salvage only the
 * gnome-writable {@code decisions/} subtree ({@link FactoryOwnedPaths}).
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract; FR23 of add-sandbox-core.
 *
 * @param exact the paths permitted by name
 * @param decisions whether any path under the gnome-writable subtree is permitted
 */
record PathPermit(Set<String> exact, boolean decisions) {

    PathPermit {
        exact = Set.copyOf(exact);
    }

    /** No path under the state directory: the host round, whose request never enters the tree. */
    static PathPermit none() {
        return new PathPermit(Set.of(), false);
    }

    /** Exactly {@code paths}, by name. */
    static PathPermit only(String... paths) {
        return new PathPermit(Set.of(paths), false);
    }

    /** Anything under the gnome-writable subtree, and nothing else. */
    static PathPermit decisionsSubtree() {
        return new PathPermit(Set.of(), true);
    }

    boolean permits(String path) {
        return exact.contains(path) || (decisions && path.startsWith(FactoryOwnedPaths.GNOME_WRITABLE + "/"));
    }
}
