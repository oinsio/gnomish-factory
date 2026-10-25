package com.github.oinsio.gnomish.app;

import java.util.regex.Pattern;

/**
 * The base tag of a factory release: the tag of the sandbox base image the release names, in the
 * contract refusal's {@code FROM} line and in the shipped recipe's {@code GNOMISH_BASE_TAG} default.
 *
 * <p>Two shapes exist and no other is constructible: {@code X.Y} for a final release (the tag the
 * weekly rebuild moves) and the full version {@code X.Y.Z-<pre>} for a pre-release, which never
 * moves {@code X.Y} (design D6, D11 of add-sandbox-base-image). The value is not derived here: the
 * build computes it once ({@code product-version-conventions}) and {@link FactoryVersion} carries
 * it, refusing one inconsistent with its version. The numeric components and the suffix's
 * characters are the ones the release tag's shape admits ({@code scripts/release-preflight.sh}).
 *
 * @param value {@code X.Y} or {@code X.Y.Z-<pre>}; never anything else
 *     <p>Implements FR5, FR9, UX2 of add-sandbox-base-image (design D11).
 */
public record BaseTag(String value) {

    private static final String NUMBER = "(0|[1-9][0-9]*)";

    private static final Pattern SHAPE =
            Pattern.compile(NUMBER + "\\." + NUMBER + "(\\." + NUMBER + "-[0-9A-Za-z.-]+)?");

    public BaseTag {
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "base tag '" + value + "' is neither X.Y nor a pre-release version X.Y.Z-<pre>");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
