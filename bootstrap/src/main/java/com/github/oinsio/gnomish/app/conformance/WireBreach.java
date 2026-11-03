package com.github.oinsio.gnomish.app.conformance;

import com.github.oinsio.gnomish.executorcontract.WireDocument;
import com.github.oinsio.gnomish.executorcontract.WireViolation;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;

/**
 * One breach of a schema file by a document on the wire, with the document it was found in (FR24
 * of define-executor-contract, design D19): what a case fails on although the factory's lenient
 * reader produced a value.
 *
 * <p>Implements FR24 of define-executor-contract.
 *
 * @param document which document broke its schema; never null
 * @param violation the validator's verdict; never null
 */
@NullMarked
public record WireBreach(WireDocument document, WireViolation violation) {

    /** The most characters of the validator's message a detail quotes. */
    static final int MESSAGE_CAP_CHARS = 300;

    /**
     * Every violation of one document, each as a breach of it.
     *
     * @param document the document judged; never null
     * @param violations the validator's verdicts; never null
     * @return the breaches, in the validator's order; immutable
     */
    public static List<WireBreach> of(WireDocument document, List<WireViolation> violations) {
        return violations.stream().map(v -> new WireBreach(document, v)).toList();
    }

    /**
     * The detail a failed case prints for this breach: the document, where in it, the keyword, and
     * the validator's message.
     *
     * @return the detail; never blank
     */
    public String detail() {
        String where = violation.path().isEmpty() ? "" : " at " + violation.path();
        return "the " + document.name().toLowerCase(Locale.ROOT) + " document breaks its schema" + where + " ("
                + violation.keyword() + "): " + violation.message().excerpt(MESSAGE_CAP_CHARS);
    }
}
