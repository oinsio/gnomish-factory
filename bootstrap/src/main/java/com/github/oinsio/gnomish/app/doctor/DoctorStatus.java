package com.github.oinsio.gnomish.app.doctor;

import java.util.Locale;
import org.jspecify.annotations.NullMarked;

/**
 * The status of one doctor result (FR1, FR2, FR6 of add-doctor-command). {@link #SKIPPED} is a
 * fourth status rather than a flavour of {@link #OK}: a check that did not run — no project
 * resolves (FR2), the pipeline did not load, no stage binds the adapter it is about — has proved
 * nothing, so it must not read as a pass in the text or in the JSON document; like a warning it
 * never fails the run.
 *
 * <p>Implements FR1, FR2, FR6 of add-doctor-command.
 */
@NullMarked
public enum DoctorStatus {
    /** The check ran and passed, or is informational. */
    OK,
    /** The check found something to look at that does not stop the factory. */
    WARN,
    /** The check found what would stop the factory. */
    FAIL,
    /** The check did not run; its detail says why. */
    SKIPPED;

    /**
     * The status as both renderings spell it — the head of a text line and the {@code status}
     * field of the {@code --json} document (FR5): the constant's name in lower case.
     */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }
}
