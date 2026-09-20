package com.github.oinsio.gnomish.domain.pipeline;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

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
     * One-line human-readable form {@code <file>: <where>: <message>} — the shape reporting
     * presents to the configuration author (UX2), and the manifest family's one mint (design D10
     * of type-untrusted-text).
     *
     * <p>The record's three components stay factory-authored {@code String}s: the message is a
     * template the factory wrote, and its 97 constructor sites span the domain's own rules, the
     * {@code .gnomish/} loader, the vendor bundle and the SPI validator interfaces a third-party
     * plugin implements — minting at each of them would put minting outside the mint table and
     * outside the factory. The fragment a message quotes (a key, a type token, a URL) is the
     * untrusted part, and it leaves the loader here, in the one line that owns it.
     *
     * @return the located error as manifest text; never blank
     */
    public UntrustedText render() {
        return UntrustedText.manifest(file + ": " + where + ": " + message);
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
