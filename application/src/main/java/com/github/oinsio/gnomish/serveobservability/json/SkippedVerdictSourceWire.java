package com.github.oinsio.gnomish.serveobservability.json;

import com.github.oinsio.gnomish.app.sandboxlifecycle.SkippedVerdictSource;
import org.jspecify.annotations.Nullable;

/**
 * The snapshot contract's {@code vitals.sweep.lastSkipSource} tokens, both directions in one
 * table: {@link SnapshotJsonMapper} writes through {@link #toWire} and {@link SnapshotJsonReader}
 * reads through {@link #fromWire}, so the writer and the reader of this token cannot drift — the
 * pair the two classes form for their other tokens (`manual-sync-pairs.md`) gains no new row to
 * keep in step by hand (D17, F90 of tighten-spec-contract).
 *
 * <p>Null travels as null both ways: a tick that skipped nothing has no source, and a document
 * written before the field existed omits it, which ADR 0016 makes an additive change. An
 * unrecognized token fails loudly, like every other token {@link SnapshotJsonReader} reads.
 *
 * <p>Implements NFR-O3 of add-serve-sandbox-lifecycle.
 */
final class SkippedVerdictSourceWire {

    private SkippedVerdictSourceWire() {}

    /**
     * Writes {@code source} as its wire token.
     *
     * @param source the domain value, or null when the tick skipped nothing
     * @return its wire token, or null
     */
    static @Nullable String toWire(@Nullable SkippedVerdictSource source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case TRACKER -> "tracker";
            case RUNTIME -> "runtime";
        };
    }

    /**
     * Reads {@code token} back as the domain value.
     *
     * @param token the wire token, or null when the document carries none
     * @return the domain value, or null
     * @throws IllegalArgumentException on a token this contract version does not define
     */
    static @Nullable SkippedVerdictSource fromWire(@Nullable String token) {
        if (token == null) {
            return null;
        }
        return switch (token) {
            case "tracker" -> SkippedVerdictSource.TRACKER;
            case "runtime" -> SkippedVerdictSource.RUNTIME;
            default -> throw new IllegalArgumentException("unknown vitals.sweep.lastSkipSource: " + token);
        };
    }
}
