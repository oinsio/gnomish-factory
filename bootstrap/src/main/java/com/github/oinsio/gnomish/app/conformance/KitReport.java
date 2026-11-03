package com.github.oinsio.gnomish.app.conformance;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/**
 * The conformance kit's verdict on one candidate (FR12, UX2 of define-executor-contract): one
 * verdict per case, in the kit's case order, rendered one line per case.
 *
 * <p>Implements FR12, UX2 of define-executor-contract.
 *
 * @param verdicts the verdict of every case; never null
 */
@NullMarked
public record KitReport(Map<KitCase, CaseVerdict> verdicts) {

    public KitReport {
        verdicts = Collections.unmodifiableMap(new EnumMap<>(verdicts));
    }

    /**
     * The report's lines, one per case in the kit's order.
     *
     * @return the lines, without line ends; immutable
     */
    public List<String> lines() {
        return verdicts.entrySet().stream()
                .map(entry -> entry.getValue().line(entry.getKey()))
                .toList();
    }

    /**
     * How many cases failed.
     *
     * @return the count; zero when the candidate passes the kit
     */
    public long failures() {
        return verdicts.values().stream()
                .filter(CaseVerdict.Failed.class::isInstance)
                .count();
    }
}
