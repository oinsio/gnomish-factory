package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.DoNotMutate;
import org.jspecify.annotations.Nullable;

/**
 * One structured problem reported by a failing verify check — the unit of
 * feedback a {@link Verdict.Fail} carries. Findings are the quality signal fed
 * back into the next executor request on a quality failure (design D3): the
 * {@code message} says what is wrong, while the optional {@code location} and
 * {@code details} locate and elaborate it for both the executor and the human
 * report.
 *
 * <p>Inert value data compared by content; a blank {@code message} is rejected
 * because a finding that names no problem carries no signal. The optional
 * locators are free-text and interpreted by no one — the engine passes them
 * through unmodified.
 *
 * <p>Implements FR4 of add-stage-engine.
 *
 * @param message what is wrong; never blank
 * @param location an optional locator (file/line/etc.), or {@code null} if none
 * @param details optional extra detail, or {@code null} if none
 */
public record Finding(
        String message, @Nullable String location, @Nullable String details) {

    public Finding {
        message = requireNonBlank(message, "message");
    }

    /**
     * Fails fast on a blank {@code message}: a finding with no message carries no
     * signal (FR4). Kept as an explicit static method rather than inline in the
     * compact constructor: PIT's record filter suppresses all mutations inside a
     * record's canonical constructor, which would silently exempt this validation
     * from the 100% mutation gate.
     *
     * <p>PIT M4 documented exception (build.gradle has the full rationale):
     * {@code @DoNotMutate} because PIT's Gregor engine crashes its own minion JVM
     * (RUN_ERROR, not a real test gap) mutating some bytecode shapes of this
     * record's component-adjacent private methods on JDK 17+
     * (hcoles/pitest#1285, a JVMTI RedefineClasses restriction on
     * NestHost/NestMembers/Record attributes — not fixable via PIT config).
     * Otherwise fully covered by FindingSpec.
     */
    @DoNotMutate
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Finding." + component + " must not be blank");
        }
        return value;
    }
}
