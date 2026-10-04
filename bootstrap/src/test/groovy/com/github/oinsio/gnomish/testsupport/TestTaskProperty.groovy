package com.github.oinsio.gnomish.testsupport

/**
 * The system properties bootstrap's {@code test} task hands its specs — build outputs such as the
 * boot jar, the archives and the SBOM, and the product version — read in one place, so a spec run
 * outside that task fails naming the property and where it is wired instead of on a null.
 */
class TestTaskProperty {

    private TestTaskProperty() {}

    /** The value of {@code name}, which bootstrap's {@code test} task must have set. */
    static String required(String name) {
        def value = System.getProperty(name)
        assert value != null: "${name} is not set (see bootstrap/verification.gradle and packaging.gradle)"
        value
    }
}
