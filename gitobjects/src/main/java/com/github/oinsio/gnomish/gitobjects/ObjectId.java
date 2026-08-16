package com.github.oinsio.gnomish.gitobjects;

/**
 * A validated git object name: a lowercase hexadecimal SHA of a commit, tree, or blob. Wrapping the
 * raw string keeps object names from being confused with refs, paths, or arbitrary inert bytes read
 * from an environment (NFR-S3) as they flow into git arguments.
 *
 * <p>Accepts the two hash lengths git uses — 40 hex (SHA-1) and 64 hex (SHA-256) — so the library
 * works against either object format without a config probe.
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
public record ObjectId(String hex) {

    @SuppressWarnings({"ConstantValue", "ConstantConditions"}) // defensive: guards construction
    // paths NullAway cannot see (e.g. Groovy specs), where a null argument would otherwise reach
    // here unchecked
    public ObjectId {
        if (hex == null || (hex.length() != 40 && hex.length() != 64)) {
            throw new IllegalArgumentException("object id must be 40 or 64 hex chars: " + hex);
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hexDigit) {
                throw new IllegalArgumentException("object id must be lowercase hex: " + hex);
            }
        }
    }

    /** Parses trimmed git output (e.g. {@code rev-parse} stdout) into a validated object id. */
    public static ObjectId of(String raw) {
        return new ObjectId(raw.trim());
    }
}
