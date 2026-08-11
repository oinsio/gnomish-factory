package com.github.oinsio.gnomish;

import org.jspecify.annotations.Nullable;

/**
 * The operator-configured resource limits the container adapter applies at
 * container creation (design D2): CPUs, memory, PID count, and working-volume
 * disk size (FR10 of add-sandbox-core). Bound as the nested {@code
 * factory.sandbox.limits.*} component of {@link SandboxProperties} via
 * constructor binding, with documented defaults for every knob so an operator
 * who sets none still runs bounded.
 *
 * <p>The units follow the container runtime's own flag grammar and are carried
 * as opaque strings the container adapter passes straight to {@code docker run}
 * ({@code --cpus}, {@code --memory}, {@code --pids-limit}, volume size); this
 * change validates only that a set value is non-blank and a set count positive —
 * the runtime is the authority on unit spelling, and pre-parsing it here would
 * duplicate that grammar and reject values a newer runtime accepts. Applying the
 * limits is the container adapter's concern (task 4.3); this record only carries
 * them.
 *
 * <p>Implements FR10 of add-sandbox-core.
 *
 * @param cpus the {@code --cpus} decimal allowance ({@code factory.sandbox.limits.cpus});
 *     defaults to {@code "2"} when unset; rejected if blank
 * @param memory the {@code --memory} allowance, runtime units ({@code
 *     factory.sandbox.limits.memory}); defaults to {@code "2g"} when unset;
 *     rejected if blank
 * @param pids the {@code --pids-limit} process cap ({@code
 *     factory.sandbox.limits.pids}); defaults to {@code 512} when unset; rejected
 *     if negative
 * @param disk the working-volume size, runtime units ({@code
 *     factory.sandbox.limits.disk}); defaults to {@code "10g"} when unset;
 *     rejected if blank
 */
public record ResourceLimits(String cpus, String memory, long pids, String disk) {

    private static final String DEFAULT_CPUS = "2";
    private static final String DEFAULT_MEMORY = "2g";
    private static final long DEFAULT_PIDS = 512;
    private static final String DEFAULT_DISK = "10g";

    // The Duration/String components are @Nullable because Spring's reflective constructor binding
    // can pass null for an unset property despite this package's @NullMarked contract; pids is a
    // primitive long, so Spring supplies 0 for the unset case, handled as the sentinel below.
    public ResourceLimits(@Nullable String cpus, @Nullable String memory, long pids, @Nullable String disk) {
        this.cpus = defaultCpus(cpus);
        this.memory = defaultMemory(memory);
        this.pids = defaultPids(pids);
        this.disk = defaultDisk(disk);
    }

    /**
     * The all-defaults limits — the bound value when {@code factory.sandbox}
     * declares no {@code limits} block at all, so {@link SandboxProperties} never
     * carries a null limits component and callers never null-check.
     *
     * @return the documented default limits; never null
     */
    public static ResourceLimits defaults() {
        return new ResourceLimits(null, null, 0, null);
    }

    /**
     * Resolves the unset case to the {@code "2"} CPU default (FR10). Kept as an
     * explicit method rather than inline in the compact constructor: PIT's record
     * filter suppresses all mutations inside a record's canonical constructor,
     * which would silently exempt the validation from the mutation gate.
     */
    private static String defaultCpus(@Nullable String cpus) {
        return defaultNonBlank(cpus, DEFAULT_CPUS, "cpus");
    }

    /** Resolves the unset case to the {@code "2g"} memory default (FR10); see {@link #defaultCpus}. */
    private static String defaultMemory(@Nullable String memory) {
        return defaultNonBlank(memory, DEFAULT_MEMORY, "memory");
    }

    /** Resolves the unset case to the {@code "10g"} disk default (FR10); see {@link #defaultCpus}. */
    private static String defaultDisk(@Nullable String disk) {
        return defaultNonBlank(disk, DEFAULT_DISK, "disk");
    }

    /**
     * Shared unset-to-default resolution for the string-valued knobs: {@code null}
     * (property unset) yields the default, an explicitly blank value is a
     * configuration mistake and is rejected (FR10).
     */
    private static String defaultNonBlank(@Nullable String value, String fallback, String knob) {
        if (value == null) {
            return fallback;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("factory.sandbox.limits." + knob + " must not be blank");
        }
        return value;
    }

    /**
     * Resolves the unset case (primitive 0 from Spring) to the {@code 512} PID
     * default (FR10); a negative value is a configuration mistake. Kept as an
     * explicit method for the same PIT record-constructor reason as {@link
     * #defaultCpus}.
     */
    private static long defaultPids(long pids) {
        if (pids < 0) {
            throw new IllegalArgumentException("factory.sandbox.limits.pids must not be negative");
        }
        return pids == 0 ? DEFAULT_PIDS : pids;
    }
}
