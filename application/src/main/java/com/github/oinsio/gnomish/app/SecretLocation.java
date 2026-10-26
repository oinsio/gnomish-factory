package com.github.oinsio.gnomish.app;

import java.util.List;

/**
 * The answer of a {@link SecretLocator}: where a secret was found, or the places searched for it.
 * Names places only, never a value (NFR-S1 of add-doctor-command).
 *
 * <p>Implements FR1, NFR-S1 of add-doctor-command.
 */
public sealed interface SecretLocation {

    /**
     * The secret resolved to a value.
     *
     * @param place the place it was read from, as the operator would look for it
     */
    record Found(String place) implements SecretLocation {}

    /**
     * The secret resolved to nothing.
     *
     * @param searched every place searched, in the resolution's order
     */
    record Absent(List<String> searched) implements SecretLocation {

        public Absent {
            searched = List.copyOf(searched);
        }
    }
}
