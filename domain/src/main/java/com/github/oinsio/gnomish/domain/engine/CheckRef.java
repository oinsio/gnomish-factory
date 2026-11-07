package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

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
 * @param label the derived human-readable identity {@code <type>:<discriminator>} — manifest
 *     text, since every discriminator in it comes from the target repository's own
 *     {@code .gnomish/} (design D3, D4 of type-untrusted-text); never blank
 */
public record CheckRef(int index, UntrustedText label) {

    public CheckRef {
        requireNonNegative(index);
        requireNonBlank(label);
    }

    /**
     * Derives a {@code CheckRef} for a check at {@code index} in a stage's verify
     * list, computing its label by an exhaustive switch over the sealed
     * {@link VerifyCheck} variants (design D3): an executor check by the token it was
     * declared with — {@code builtin:<name>}, {@code command:<command>}, {@code
     * external:<executor>:<checkId>}, {@code executor:<executor>:<checkId>} — and {@code Judge}
     * → {@code judge:<criteriaFile>}. No {@code default} arm, so a new variant or token fails
     * to compile until its label mapping is added.
     *
     * <p>A polled check's label carries its executor as well as its {@code checkId} because
     * the identity findings are correlated under across attempts is the pair, not the id alone
     * (FR8 of add-plugin-architecture): two executors may each serve a check named {@code
     * ci/build}, and merging their findings would attribute one platform's failures to the
     * other.
     *
     * <p>Implements FR4 of add-stage-engine; FR8 of add-plugin-architecture; FR1 of
     * migrate-verify-to-executor-contract.
     *
     * @param index the zero-based position in the stage's ordered verify list
     * @param check the check whose type and salient field derive the label
     * @return the identity of the check at that position
     */
    public static CheckRef of(int index, VerifyCheck check) {
        String label =
                switch (check) {
                    case VerifyCheck.Executor executor -> executorLabel(executor);
                    case VerifyCheck.Judge(String criteriaFile, var _, var _, var _) -> "judge:" + criteriaFile;
                };
        // The manifest mint (design D3): every discriminator in the label — a builtin's name, a
        // command line, a provider and check id, a criteria file — is text the target repository's
        // own `.gnomish/` wrote, so the label leaves the manifest as manifest text.
        return new CheckRef(index, UntrustedText.manifest(label));
    }

    /**
     * The label of an executor check, prefixed by the token it was declared with, so a
     * legacy check keeps the label shape it had before the tokens became sugar (FR1, UX2
     * of migrate-verify-to-executor-contract).
     */
    private static String executorLabel(VerifyCheck.Executor check) {
        return switch (check.token()) {
            case BUILTIN -> "builtin:" + check.checkId();
            case COMMAND -> "command:" + check.checkId();
            case EXTERNAL -> "external:" + check.executorName() + ":" + check.checkId();
            case EXECUTOR -> "executor:" + check.executorName() + ":" + check.checkId();
        };
    }

    /**
     * Fails fast on a negative {@code index}: a verify-list position cannot be
     * negative (FR4). Kept as an explicit static method rather than inline in the
     * compact constructor: PIT's record filter suppresses all mutations inside a
     * record's canonical constructor, which would silently exempt this validation
     * from the 100% mutation gate.
     */
    private static void requireNonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("CheckRef.index must not be negative");
        }
    }

    /**
     * Fails fast on a blank {@code label}: the label is the check's human identity
     * (FR4). Kept as an explicit static method rather than inline in the compact
     * constructor: PIT's record filter suppresses all mutations inside a record's
     * canonical constructor, which would silently exempt this validation from the
     * 100% mutation gate.
     */
    private static void requireNonBlank(UntrustedText value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("CheckRef.label must not be blank");
        }
    }
}
