package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;

/**
 * The identity of a single verify check within a stage: its zero-based position
 * in the stage's ordered {@code verify} list plus a derived human-readable label.
 * A check carries no explicit id in the manifest (that was deferred, NG6), so the
 * engine identifies it by position + label of the form {@code <type>:<discriminator>}
 * (design D3) — e.g. {@code command:./gradlew test} — which is what escalation
 * reports and telemetry name.
 *
 * <p>The {@code index} is zero-based to match Java list indexing: it is the exact
 * position in the stage's {@code verify} list, so the first check is index 0. A
 * verify-list position cannot be negative, so a negative index is rejected; the
 * label is the check's human identity, so a blank label is rejected. Inert value
 * data compared by content.
 *
 * <p>Implements FR4 of add-stage-engine.
 *
 * @param index the zero-based position in the stage's ordered verify list; never
 *     negative
 * @param label the derived human-readable identity {@code <type>:<discriminator>};
 *     never blank
 */
public record CheckRef(int index, String label) {

    public CheckRef {
        index = requireNonNegative(index, "index");
        label = requireNonBlank(label, "label");
    }

    /**
     * Derives a {@code CheckRef} for a check at {@code index} in a stage's verify
     * list, computing its label by an exhaustive switch over the sealed
     * {@link VerifyCheck} variants (design D3): {@code Builtin} → {@code builtin:<name>},
     * {@code Command} → {@code command:<command>}, {@code External} → {@code external:<checkId>},
     * {@code Judge} → {@code judge:<criteriaFile>}. No {@code default} arm, so a new
     * variant fails to compile until its label mapping is added.
     *
     * <p>Implements FR4 of add-stage-engine.
     *
     * @param index the zero-based position in the stage's ordered verify list
     * @param check the check whose type and salient field derive the label
     * @return the identity of the check at that position
     */
    public static CheckRef of(int index, VerifyCheck check) {
        String label =
                switch (check) {
                    case VerifyCheck.Builtin(String name, var params) -> "builtin:" + name;
                    case VerifyCheck.Command(String command) -> "command:" + command;
                    case VerifyCheck.External(String checkId, var interval, var timeout) -> "external:" + checkId;
                    case VerifyCheck.Judge(String criteriaFile, var model, var settings, var votes) ->
                        "judge:" + criteriaFile;
                };
        return new CheckRef(index, label);
    }

    /**
     * Fails fast on a negative {@code index}: a verify-list position cannot be
     * negative (FR4). Kept as an explicit static method rather than inline in the
     * compact constructor: PIT's record filter suppresses all mutations inside a
     * record's canonical constructor, which would silently exempt this validation
     * from the 100% mutation gate.
     */
    private static int requireNonNegative(int value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("CheckRef." + component + " must not be negative");
        }
        return value;
    }

    /**
     * Fails fast on a blank {@code label}: the label is the check's human identity
     * (FR4). Kept as an explicit static method rather than inline in the compact
     * constructor: PIT's record filter suppresses all mutations inside a record's
     * canonical constructor, which would silently exempt this validation from the
     * 100% mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("CheckRef." + component + " must not be blank");
        }
        return value;
    }
}
