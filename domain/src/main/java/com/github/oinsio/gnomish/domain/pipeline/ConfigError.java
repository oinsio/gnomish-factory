package com.github.oinsio.gnomish.domain.pipeline;

/**
 * A single located validation problem found in a {@code .gnomish/} configuration
 * tree. Every error names its location — file plus field/stage locator — so the
 * author can fix it without guessing (NFR-O1). Errors are aggregated as data into
 * {@link LoadOutcome.Invalid}, never thrown (design D3).
 *
 * <p>Implements FR8 of load-pipeline-config.
 *
 * @param file relative path of the offending file within {@code .gnomish/}
 *     (e.g. {@code stages/build/stage.yaml}); never blank
 * @param where the field or stage at fault within the file
 *     (e.g. {@code mechanism.executor}); never blank
 * @param message what is wrong (e.g. {@code unknown executor 'foo'}); never blank
 */
public record ConfigError(String file, String where, String message) {

    public ConfigError {
        file = requireNonBlank(file, "file");
        where = requireNonBlank(where, "where");
        message = requireNonBlank(message, "message");
    }

    /**
     * One-line human-readable form {@code <file>: <where>: <message>} — the shape
     * future reporting presents to the configuration author (UX2).
     */
    public String render() {
        return file + ": " + where + ": " + message;
    }

    /**
     * Fails fast on a blank component: an error that cannot name its location or
     * problem is useless to the author (NFR-O1). Kept as an explicit static method
     * rather than inline in the compact constructor: PIT's record filter suppresses
     * all mutations inside a record's canonical constructor, which would silently
     * exempt this validation from the 100% mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("ConfigError." + component + " must not be blank");
        }
        return value;
    }
}
