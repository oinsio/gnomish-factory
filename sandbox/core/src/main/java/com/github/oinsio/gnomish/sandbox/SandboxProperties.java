package com.github.oinsio.gnomish.sandbox;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Immutable typed configuration of the container adapter, bound from the {@code
 * factory.sandbox.*} external properties via constructor binding, mirroring
 * {@code FactoryProperties} (design D2, D7). Kept as an independent top-level
 * {@code @ConfigurationProperties} record rather than nested inside {@code
 * FactoryProperties} so neither file grows past the {@code
 * process-invariants.md} file-size target; {@code @ConfigurationPropertiesScan}
 * on the bootstrap application class picks it up automatically — this package is
 * below it in the scan tree.
 *
 * <p>These are the operator-owned installation knobs the container adapter and
 * the egress guard consume — the repo never sets any of them (adapter binding
 * and every weakening are operator-only, FR14). Concrete image, runtime, limits,
 * allowlist, and passthrough values are the container adapter's (task group 4)
 * and the guard's (task group 6) concern; this record only carries them, typed.
 *
 * <p>Implements FR3, FR7, FR9, FR10 of add-sandbox-core.
 *
 * @param image the operator-supplied container image ({@code
 *     factory.sandbox.image}); {@code null} when unset — required only when a
 *     stage actually binds the container adapter, validated at container-adapter
 *     construction (task group 4), never here (FR3)
 * @param runtime the {@code --runtime} knob ({@code factory.sandbox.runtime});
 *     defaults to {@code "runc"} when unset so a Linux operator can switch to
 *     sysbox/gVisor without adapter changes (design D2); rejected if blank
 * @param guardImage the egress-guard proxy image ({@code
 *     factory.sandbox.guard-image}) the factory runs mitmdump from (design D4,
 *     FR7); defaults to the pinned-major official image when unset; rejected if
 *     blank
 * @param limits the resource limits ({@code factory.sandbox.limits.*}); defaults
 *     to {@link ResourceLimits#defaults()} when unset (FR10)
 * @param egressAllowlist the operator-owned default-deny egress allowlist ({@code
 *     factory.sandbox.egress-allowlist}) the guard renders (FR7); defaults to an
 *     empty list — default-deny with nothing allowed — when unset
 * @param envPassthrough the exact names of environment variables passed through
 *     to child processes ({@code factory.sandbox.env-passthrough}), values read
 *     live from the factory environment at exec time (design D6, FR9); defaults
 *     to an empty list when unset. Credential names are refused at the
 *     allowlist-construction seam ({@code ChildEnvAllowlist}), never in this
 *     carrier
 * @param enforceDiskQuota whether the container adapter adds {@code
 *     --storage-opt size=} at container creation ({@code
 *     factory.sandbox.enforce-disk-quota}, FR10); opt-in — the flag needs a
 *     quota-capable storage driver (overlay2 on xfs with {@code pquota}) most
 *     daemons lack, so defaulting it on would fail every container start
 */
@ConfigurationProperties("factory.sandbox")
public record SandboxProperties(
        @Nullable String image,
        String runtime,
        String guardImage,
        ResourceLimits limits,
        List<String> egressAllowlist,
        List<String> envPassthrough,
        boolean enforceDiskQuota) {

    private static final String DEFAULT_RUNTIME = "runc";

    /**
     * The official mitmproxy image pinned to its current major, so change B's TLS
     * opening is a mode switch on the same tool (design D4); operators override
     * via {@code factory.sandbox.guard-image} (e.g. a private mirror).
     */
    static final String DEFAULT_GUARD_IMAGE = "mitmproxy/mitmproxy:12";

    // Every component but image is defaulted rather than left null: Spring's reflective constructor
    // binding can pass null for an unset property despite this package's @NullMarked contract, and
    // SandboxPropertiesSpec constructs this record with explicit nulls to exercise exactly that path.
    // image stays @Nullable by design — "unset" is a legitimate state (host-only installs).
    public SandboxProperties(
            @Nullable String image,
            @Nullable String runtime,
            @Nullable String guardImage,
            @Nullable ResourceLimits limits,
            @Nullable List<String> egressAllowlist,
            @Nullable List<String> envPassthrough,
            boolean enforceDiskQuota) {
        this.image = image;
        this.runtime = defaulted(runtime, DEFAULT_RUNTIME, "factory.sandbox.runtime");
        this.guardImage = defaulted(guardImage, DEFAULT_GUARD_IMAGE, "factory.sandbox.guard-image");
        this.limits = limits == null ? ResourceLimits.defaults() : limits;
        this.egressAllowlist = egressAllowlist == null ? List.of() : List.copyOf(egressAllowlist);
        this.envPassthrough = envPassthrough == null ? List.of() : List.copyOf(envPassthrough);
        this.enforceDiskQuota = enforceDiskQuota;
    }

    /**
     * Resolves the unset case to {@code fallback} (design D2, D4); a value
     * explicitly set to blank is a configuration mistake and is rejected naming
     * the property. Kept as an explicit method rather than inline in the compact
     * constructor: PIT's record filter suppresses all mutations inside a record's
     * canonical constructor, which would silently exempt the validation from the
     * mutation gate.
     *
     * <p>PIT M4 documented exception (build.gradle has the full rationale):
     * {@code @DoNotMutate} because PIT's Gregor engine crashes its own minion JVM
     * (RUN_ERROR, not a real test gap) mutating some bytecode shapes of this
     * record's private static methods on JDK 17+ (hcoles/pitest#1285, a JVMTI
     * RedefineClasses restriction on NestHost/NestMembers/Record attributes).
     * Otherwise fully covered by SandboxPropertiesSpec (defaults, overrides, and
     * both blank-rejection legs).
     */
    @DoNotMutate
    private static String defaulted(@Nullable String value, String fallback, String property) {
        if (value == null) {
            return fallback;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be blank");
        }
        return value;
    }
}
