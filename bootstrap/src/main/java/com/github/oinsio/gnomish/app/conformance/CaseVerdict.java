package com.github.oinsio.gnomish.app.conformance;

import org.jspecify.annotations.NullMarked;

/**
 * What the conformance kit decided for one case (FR12, UX2 of define-executor-contract): passed,
 * failed with the first offending detail, or not applicable with why.
 *
 * <p>Implements FR12, UX2 of define-executor-contract.
 */
@NullMarked
public sealed interface CaseVerdict {

    /** The most characters of a detail or a reason a report line carries. */
    int DETAIL_CAP_CHARS = 400;

    /** The case passed. */
    record Passed() implements CaseVerdict {}

    /**
     * The case failed.
     *
     * @param detail the first offending detail; never blank
     */
    record Failed(String detail) implements CaseVerdict {}

    /**
     * The case does not apply to this candidate.
     *
     * @param reason why it was not run; never blank
     */
    record NotApplicable(String reason) implements CaseVerdict {}

    /**
     * The report line of {@code kase} under this verdict: {@code pass}, {@code FAIL} or {@code n/a},
     * the case's name, and the detail or the reason.
     *
     * @param kase the case this verdict is for; never null
     * @return the line, without a line end; never blank
     */
    default String line(KitCase kase) {
        return switch (this) {
            case Passed ignored -> "pass " + kase.word();
            case Failed failed -> "FAIL " + kase.word() + ": " + capped(failed.detail());
            case NotApplicable skipped -> "n/a  " + kase.word() + ": " + capped(skipped.reason());
        };
    }

    private static String capped(String text) {
        return text.length() <= DETAIL_CAP_CHARS ? text : text.substring(0, DETAIL_CAP_CHARS) + "…";
    }
}
