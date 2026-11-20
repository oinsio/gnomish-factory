package com.github.oinsio.gnomish.app

import java.nio.file.Path

/**
 * Described laws for specs outside this package that build registry entries by hand: the program
 * answers and the admitted connections a spec chooses.
 */
final class DescribedLaws {

    private DescribedLaws() {}

    static DescribedLaw none() {
        connected([:])
    }

    /** The law's programs described by {@code answers}, by name; no connection admitted. */
    static DescribedLaw described(Map<String, com.github.oinsio.gnomish.executorcontract.Describe> answers) {
        new DescribedLaw(LawBinding.workingTree(Path.of('/tmp/law')), answers, [:])
    }

    static DescribedLaw connected(Map<String, AdmittedConnection> connections) {
        new DescribedLaw(LawBinding.workingTree(Path.of('/tmp/law')), [:], connections)
    }
}
