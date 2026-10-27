package com.github.oinsio.gnomish.app.doctor

import java.nio.file.Path

/**
 * One row of the doctor's identity table (M1 of add-doctor-command): a check of FR1 and the
 * fixtures it is judged on, each run once through the startup path's owner and once through the
 * doctor check, both over the same fixture.
 */
final class IdentityRow {

    /** The check the row is about. */
    final DoctorCheckId id

    /** Builds the row's cases in a fresh directory of the feature's own. */
    final Closure<List<Case>> cases

    IdentityRow(DoctorCheckId id, Closure<List<Case>> cases) {
        this.id = id
        this.cases = cases
    }

    List<Case> run(Path dir) {
        cases.call(dir) as List<Case>
    }

    @Override
    String toString() {
        id.id()
    }

    /**
     * One fixture's two answers: whether the startup path stops on it and what it says, and the
     * doctor check's lines over the same fixture.
     */
    static final class Case {

        final String label
        final boolean startupFails
        final String startupSays
        final List<DoctorVerdict> doctor

        /** The words both answers carry: the shared reason of a refusal, or the value both read. */
        final List<String> shared

        Case(String label, boolean startupFails, String startupSays, List<DoctorVerdict> doctor, List<String> shared) {
            this.label = label
            this.startupFails = startupFails
            this.startupSays = startupSays
            this.doctor = doctor
            this.shared = shared
        }

        /** A case whose startup side is a refusing owner: {@code refusal} is what it threw, or {@code null}. */
        static Case refusing(String label, Throwable refusal, List<DoctorVerdict> doctor, List<String> shared) {
            new Case(label, refusal != null, refusal == null ? '' : refusal.message, doctor, shared)
        }

        boolean doctorFails() {
            doctor.any { it.status() == DoctorStatus.FAIL }
        }

        String doctorSays() {
            doctor*.detail().join('\n')
        }
    }

    /** What {@code action} threw of {@code type}, or {@code null} when it returned; any other throwable propagates. */
    static <T extends Throwable> T refusalOf(Class<T> type, Closure<?> action) {
        try {
            action.call()
            return null
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return type.cast(thrown)
            }
            throw thrown
        }
    }
}
