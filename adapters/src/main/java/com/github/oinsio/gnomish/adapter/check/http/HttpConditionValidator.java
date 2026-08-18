package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Grades one {@code pass-when} / {@code pending-when} block of an {@code http} check (FR6, FR10 of
 * add-plugin-architecture). Split out of {@link HttpCheckParamsValidator} to keep both files within
 * the project's file-size guidance; the rules are the condition's own.
 *
 * <p>The rules exist because a silently meaningless condition is worse than a rejected one: an
 * {@code equals} with nothing to extract compares against nothing, an extractor with no {@code
 * equals} has no verdict to reach, and a {@code pending-when} that asserts nothing about the body
 * would match every response and poll the check to its timeout — a stage failure with no cause an
 * author could read off the manifest.
 *
 * <p>Implements FR6, FR10 of add-plugin-architecture.
 */
final class HttpConditionValidator {

    private static final Set<String> KEYS =
            Set.of(HttpCheckCondition.JSON_PATH_KEY, HttpCheckCondition.REGEX_KEY, HttpCheckCondition.EQUALS_KEY);

    private HttpConditionValidator() {}

    /**
     * Validates one condition block.
     *
     * @param file the stage manifest the check was declared in
     * @param where the located field prefix of this block (e.g. {@code verify[0].params.pass-when})
     * @param raw the block's raw content; never null
     * @param extractorRequired whether the block is meaningless without an extractor — true for
     *     {@code pending-when}, false for {@code pass-when}, whose bare form is the 2xx default
     * @param errors the caller's accumulator, appended to in key order
     */
    static void validate(
            String file, String where, Map<String, Object> raw, boolean extractorRequired, List<ConfigError> errors) {
        for (String key : new TreeSet<>(raw.keySet())) {
            if (!KEYS.contains(key)) {
                errors.add(new ConfigError(
                        file, where + "." + key, "unknown key '%s'; expected one of %s".formatted(key, sorted())));
            } else if (!(raw.get(key) instanceof String value) || value.isBlank()) {
                errors.add(new ConfigError(file, where + "." + key, "'%s' must be a non-blank string".formatted(key)));
            }
        }
        requireRegexCompiles(file, where, raw, errors);
        requirePairing(file, where, raw, extractorRequired, errors);
    }

    /** An extractor and its {@code equals} only mean something together (FR10). */
    private static void requirePairing(
            String file, String where, Map<String, Object> raw, boolean extractorRequired, List<ConfigError> errors) {
        boolean hasExtractor =
                raw.containsKey(HttpCheckCondition.JSON_PATH_KEY) || raw.containsKey(HttpCheckCondition.REGEX_KEY);
        boolean hasEquals = raw.containsKey(HttpCheckCondition.EQUALS_KEY);
        if (hasExtractor && !hasEquals) {
            errors.add(new ConfigError(
                    file,
                    where,
                    "declares an extractor but no '%s' to compare it with".formatted(HttpCheckCondition.EQUALS_KEY)));
        }
        if (hasEquals && !hasExtractor) {
            errors.add(new ConfigError(
                    file,
                    where,
                    "declares '%s' but neither '%s' nor '%s' to extract a value with"
                            .formatted(
                                    HttpCheckCondition.EQUALS_KEY,
                                    HttpCheckCondition.JSON_PATH_KEY,
                                    HttpCheckCondition.REGEX_KEY)));
        }
        if (extractorRequired && !hasExtractor) {
            errors.add(new ConfigError(
                    file,
                    where,
                    ("must declare '%s' and/or '%s'; a condition asserting nothing about the body would"
                                    + " match every response and poll until the check times out")
                            .formatted(HttpCheckCondition.JSON_PATH_KEY, HttpCheckCondition.REGEX_KEY)));
        }
    }

    /** A pattern that cannot compile never matches, which would read as a permanently failing check. */
    private static void requireRegexCompiles(
            String file, String where, Map<String, Object> raw, List<ConfigError> errors) {
        if (!(raw.get(HttpCheckCondition.REGEX_KEY) instanceof String regex) || regex.isBlank()) {
            return;
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            errors.add(new ConfigError(
                    file,
                    where + "." + HttpCheckCondition.REGEX_KEY,
                    "is not a valid regular expression: " + e.getDescription()));
        }
    }

    private static Set<String> sorted() {
        return new TreeSet<>(KEYS);
    }
}
