package com.github.oinsio.gnomish.app.port.tracker;

import java.security.SecureRandom;

/**
 * The composite identity of a running factory process: {@code
 * <name>-<suffix>}, where {@code name} is the configured, human-set {@code
 * factory.instance-name} (diagnostic — "whose machine" — once an operator sets
 * it) and {@code suffix} is a 6-character lowercase base36 string generated
 * once per process at startup via {@link #generate(String)} (design D6, FR9).
 *
 * <p>The suffix exists purely for collision safety: a copied config file, a
 * reused OS pid, or two containers that both present as pid 1 would otherwise
 * let two distinct processes claim the same name. Atomicity of a claim itself
 * never depends on this id — the tracker's comment-id ordering decides claim
 * races (design D13) — so this type is an informational label, not a
 * coordination primitive.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR9 of add-tracker-port.
 *
 * @param name the configured instance name; never blank
 * @param suffix the 6-character lowercase base36 per-process suffix; never blank
 */
public record InstanceId(String name, String suffix) {

    private static final int SUFFIX_LENGTH = 6;
    private static final int BASE36 = 36;
    private static final SecureRandom RANDOM = new SecureRandom();

    public InstanceId {
        name = requireNonBlank(name, "name");
        suffix = requireNonBlank(suffix, "suffix");
    }

    /**
     * Generates a fresh {@code InstanceId} for {@code name}, minting a new
     * random 6-character base36 suffix. Called once per factory process at
     * startup (design D6) — every call produces a statistically distinct
     * suffix, even for repeated invocations with the same {@code name}.
     *
     * <p>{@link SecureRandom} is used rather than {@link
     * java.util.concurrent.ThreadLocalRandom}: this runs once per process
     * lifetime, so its cost is negligible, and it avoids any dependence on a
     * predictable seed (e.g. system clock at container start) that could make
     * copied-container collisions more likely than the suffix space suggests.
     *
     * @param name the configured instance name; never blank
     * @return a new {@code InstanceId} combining {@code name} with a fresh suffix
     */
    public static InstanceId generate(String name) {
        return new InstanceId(name, randomSuffix());
    }

    /** The full composite id string, e.g. {@code gnomish-factory-x7k2q1}. */
    public String value() {
        return name + "-" + suffix;
    }

    private static String randomSuffix() {
        var builder = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            builder.append(Character.forDigit(RANDOM.nextInt(BASE36), BASE36));
        }
        return builder.toString();
    }

    /**
     * Fails fast on a blank component: an instance id half with no content
     * cannot serve as a diagnostic label (FR9). Kept as an explicit static
     * method rather than inline in the compact constructor: PIT's record
     * filter suppresses all mutations inside a record's canonical
     * constructor, which would silently exempt this validation from the 100%
     * mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("InstanceId." + component + " must not be blank");
        }
        return value;
    }
}
