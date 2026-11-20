package com.github.oinsio.gnomish.pendingkey;

import org.jspecify.annotations.Nullable;

/**
 * The opaque key an executor returns with status {@code pending}, which the factory polls with. The
 * factory never interprets it, but it travels whole in the poll's {@code GNOMISH_PENDING_KEY}
 * environment variable, so it must be a value an environment can carry: not blank, no ISO control
 * character (a NUL is refused by every process launch), at most {@link #MAX_LENGTH} characters (the
 * variable rides every poll's environment). Wire shape: the executor contract's {@code pendingKey},
 * a string of 1 to {@link #MAX_LENGTH} characters with no control character; the contract's JSON
 * binding writes it as that bare string.
 *
 * <p>One type on every layer (design D20 of migrate-verify-to-executor-contract): the executor
 * contract's result and poll, the engine's pending result and pending turn all carry this record,
 * so no layer holds the key as a bare {@code String} the rule has not judged.
 *
 * <p>Implements FR2, FR23, NFR-R3 of define-executor-contract; FR2, FR3 of
 * migrate-verify-to-executor-contract.
 */
public record PendingKey(String value) {

    /** The longest key, in UTF-16 characters, the factory accepts. */
    public static final int MAX_LENGTH = 4096;

    /** Refuses a blank key and a key no environment variable can carry. */
    public PendingKey {
        requireUsable(value);
    }

    /**
     * The constructor's refusal, as a static method rather than inline in the compact constructor,
     * so PIT's record-constructor filter does not exempt the guard from the mutation gate.
     */
    private static void requireUsable(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("pendingKey must not be blank");
        }
        String unusable = unusable(value);
        if (unusable != null) {
            throw new IllegalArgumentException("pendingKey is unusable: " + unusable);
        }
    }

    /**
     * Why a non-blank written key cannot be a pending key, or {@code null} when it can; the one
     * judgement the constructor and a reader of a written key share.
     *
     * @param value a non-blank written key
     * @return the reason the key is unusable, or {@code null} when it is usable
     */
    public static @Nullable String unusable(String value) {
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
