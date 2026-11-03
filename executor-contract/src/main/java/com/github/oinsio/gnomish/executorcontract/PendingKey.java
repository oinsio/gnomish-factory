package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

/**
 * The opaque key an executor returns with status {@code pending}, which the factory polls with. The
 * factory never interprets it, but it travels whole in the poll's {@code GNOMISH_PENDING_KEY}
 * environment variable, so it must be a value an environment can carry: not blank, no ISO control
 * character (a NUL is refused by every process launch), at most {@link #MAX_LENGTH} characters (the
 * variable rides every poll's environment). Wire shape: the result schema's {@code pendingKey}, a
 * string of 1 to {@link #MAX_LENGTH} characters with no control character.
 *
 * <p>Implements FR2, FR23, NFR-R3 of define-executor-contract.
 */
public record PendingKey(@JsonValue String value) {

    /** The longest key, in UTF-16 characters, the factory accepts. */
    public static final int MAX_LENGTH = 4096;

    /** Refuses a blank key and a key no environment variable can carry. */
    public PendingKey {
        WireChecks.nonBlank(value, "pendingKey");
        String unusable = unusable(value);
        if (unusable != null) {
            throw new IllegalArgumentException("pendingKey is unusable: " + unusable);
        }
    }

    /**
     * Why a non-blank written key cannot be a pending key, or {@code null} when it can; the one
     * judgement the constructor and the result reading share.
     */
    static @Nullable String unusable(String value) {
        if (value.length() > MAX_LENGTH) {
            return "it is longer than " + MAX_LENGTH + " characters";
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            return "it holds a control character";
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}
