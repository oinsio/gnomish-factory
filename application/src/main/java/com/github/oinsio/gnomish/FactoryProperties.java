package com.github.oinsio.gnomish;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Immutable typed configuration of a factory instance, bound from the {@code factory.*} external
 * properties via constructor binding (design D4). Validation is plain Java triggered by the
 * compact constructor — directly spec-able and mutation-testable, no Bean Validation.
 *
 * <p>Implements FR3 of add-project-skeleton.
 *
 * <p>{@code agentCliBinary} is installation-level agent-executor configuration: the CLI binary
 * path. It never lives in the stage manifest (design D7). Implements FR11 of add-agent-executor.
 *
 * <p>{@code instanceName} (renamed from {@code instanceId}, design D5, D6) is the diagnostic
 * "whose machine" half of the minted per-process {@link
 * com.github.oinsio.gnomish.app.port.tracker.InstanceId}; unlike the old {@code instance-id} it
 * defaults rather than fails when unset, since D5 gives it a neutral default (the instance name
 * lands in public issue comments, so a hostname-derived default is rejected — design D6).
 * {@code tracker} carries the abort-backoff policy Duration defaults (design D5, D10).
 *
 * @param instanceName diagnostic name of this factory instance ({@code factory.instance-name});
 *     defaults to {@code "gnomish-factory"} when unset; rejected if explicitly set to blank
 * @param agentCliBinary path or name of the agent CLI binary ({@code factory.agent-cli-binary});
 *     defaults to {@code "claude"} (resolved from {@code PATH}) when unset
 * @param agentCliTailDrainGrace how long a round waits after the agent process exits for its
 *     stdout drain to deliver the already-piped tail ({@code factory.agent-cli-tail-drain-grace});
 *     defaults to 5 seconds when unset, rejected when non-positive — installation-level like
 *     {@code agentCliBinary}, since it characterizes the host rather than the repo's pipeline
 *     (FR7, design D2 of fix-round-stdout-drain); documented in the installation-properties table
 *     of {@code docs/guides/operator-guide-run.md} (UX3 of fix-round-stdout-drain)
 * @param agentCliEnvPassthrough superseded and ignored: the operator passthrough of the layered
 *     child-environment allowlist is {@code factory.sandbox.env-passthrough} (D6, FR9 of
 *     add-sandbox-core). This knob was documentation-only under the replaced
 *     inherit-everything-minus-scrub behavior and is kept solely so existing configs still bind;
 *     defaults to an empty list when unset
 * @param tracker the tracker abort-backoff policy defaults ({@code factory.tracker.*}); defaults
 *     to {@link Tracker#Tracker(Duration, Duration)}'s own defaults when unset
 * @param check the check providers' operator subsections ({@code factory.check.<provider>.*}),
 *     keyed by provider discriminator and carried as raw untyped content; defaults to an empty map
 *     (no check provider configured) when absent
 * @param connections the named per-vendor connection profiles ({@code factory.connections.<name>.*}),
 *     keyed by profile name and carried as raw untyped content, that a port subsection references as
 *     {@code connection: <name>} instead of inlining endpoint and credential-name keys (FR16, design
 *     D8 of add-plugin-architecture); defaults to an empty map (no profile defined) when absent
 */
@ConfigurationProperties("factory")
public record FactoryProperties(
        String instanceName,
        String agentCliBinary,
        Duration agentCliTailDrainGrace,
        List<String> agentCliEnvPassthrough,
        Tracker tracker,
        Map<String, Map<String, Object>> check,
        Map<String, Map<String, Object>> connections) {

    private static final String DEFAULT_INSTANCE_NAME = "gnomish-factory";
    private static final String DEFAULT_AGENT_CLI_BINARY = "claude";
    private static final Duration DEFAULT_AGENT_CLI_TAIL_DRAIN_GRACE = Duration.ofSeconds(5);

    // The tracker/agentCliEnvPassthrough null-checks below are real, not IDE dead-code noise:
    // Spring's reflective constructor binding can pass null for these record components despite
    // the compile-time @NullMarked contract, and FactoryPropertiesSpec constructs this record
    // with an explicit null for both to exercise exactly that path.
    // @ConstructorBinding is required, not decorative: the convenience constructor below makes this
    // record's constructors ambiguous to Spring's binder, which then reports "No default constructor
    // found" and fails every context. This names the canonical one as the binding target.
    @ConstructorBinding
    public FactoryProperties(
            @Nullable String instanceName,
            @Nullable String agentCliBinary,
            @Nullable Duration agentCliTailDrainGrace,
            @Nullable List<String> agentCliEnvPassthrough,
            @Nullable Tracker tracker,
            @Nullable Map<String, Map<String, Object>> check,
            @Nullable Map<String, Map<String, Object>> connections) {
        this.instanceName = defaultInstanceName(instanceName);
        this.agentCliBinary = defaultAgentCliBinary(agentCliBinary);
        this.agentCliTailDrainGrace = defaultTailDrainGrace(agentCliTailDrainGrace);
        this.agentCliEnvPassthrough = defaultEnvPassthrough(agentCliEnvPassthrough);
        this.tracker = defaultTracker(tracker);
        this.check = defaultSubsections(check);
        this.connections = defaultSubsections(connections);
    }

    /**
     * Convenience for the callers predating named connection profiles (FR16 of
     * add-plugin-architecture): no profile is defined, so every port subsection declares its
     * connection inline. Property binding ignores this one — {@code @ConstructorBinding} above names
     * the canonical constructor as the binding target, which is exactly why that annotation is
     * needed once a second constructor exists.
     */
    public FactoryProperties(
            @Nullable String instanceName,
            @Nullable String agentCliBinary,
            @Nullable List<String> agentCliEnvPassthrough,
            @Nullable Tracker tracker,
            @Nullable Map<String, Map<String, Object>> check) {
        this(instanceName, agentCliBinary, agentCliEnvPassthrough, tracker, check, null);
    }

    /**
     * Convenience for the callers predating the tail-drain grace (FR7 of fix-round-stdout-drain):
     * the grace keeps its 5-second default. Property binding ignores this one too — see the note
     * on {@code @ConstructorBinding} above.
     */
    public FactoryProperties(
            @Nullable String instanceName,
            @Nullable String agentCliBinary,
            @Nullable List<String> agentCliEnvPassthrough,
            @Nullable Tracker tracker,
            @Nullable Map<String, Map<String, Object>> check,
            @Nullable Map<String, Map<String, Object>> connections) {
        this(instanceName, agentCliBinary, null, agentCliEnvPassthrough, tracker, check, connections);
    }

    /**
     * Resolves the unset case to the neutral {@code gnomish-factory} default (design D5, D6); a
     * value explicitly set to blank (e.g. {@code factory.instance-name=""}) is still rejected —
     * that is a configuration mistake, not "unset". Kept as an explicit method rather than inline
     * in the compact constructor: PIT's record filter suppresses all mutations inside a record's
     * canonical constructor, which would silently exempt the validation logic from the 100%
     * mutation gate (FR6 of add-project-skeleton). The parameter is {@code @Nullable} because
     * framework property binding constructs the record reflectively and can pass null despite
     * this package's {@code @NullMarked} default.
     */
    private static String defaultInstanceName(@Nullable String instanceName) {
        if (instanceName == null) {
            return DEFAULT_INSTANCE_NAME;
        }
        if (instanceName.isBlank()) {
            throw new IllegalArgumentException("factory.instance-name must not be blank");
        }
        return instanceName;
    }

    /**
     * Resolves the unset case to the {@code claude}-on-PATH default (design D7). Kept as an
     * explicit method for the same PIT record-constructor reason as {@link #defaultInstanceName}.
     */
    private static String defaultAgentCliBinary(@Nullable String agentCliBinary) {
        return agentCliBinary == null ? DEFAULT_AGENT_CLI_BINARY : agentCliBinary;
    }

    /**
     * Resolves the unset case to the 5-second default and rejects a non-positive value — a grace
     * of zero or less could never absorb a piped tail, so it is a configuration mistake caught at
     * startup, before any dialog (FR7, design D2 of fix-round-stdout-drain). A malformed value
     * ({@code "banana"}) never reaches here: Spring's Duration conversion fails the bind first,
     * which is the same startup error one layer up. Kept as an explicit method for the same PIT
     * record-constructor reason as {@link #defaultInstanceName}.
     */
    private static Duration defaultTailDrainGrace(@Nullable Duration agentCliTailDrainGrace) {
        if (agentCliTailDrainGrace == null) {
            return DEFAULT_AGENT_CLI_TAIL_DRAIN_GRACE;
        }
        if (agentCliTailDrainGrace.isZero() || agentCliTailDrainGrace.isNegative()) {
            throw new IllegalArgumentException("factory.agent-cli-tail-drain-grace must be positive");
        }
        return agentCliTailDrainGrace;
    }

    /**
     * Resolves the unset case to an empty passthrough list (design D7). Kept as an explicit
     * method for the same PIT record-constructor reason as {@link #defaultInstanceName}.
     */
    private static List<String> defaultEnvPassthrough(@Nullable List<String> agentCliEnvPassthrough) {
        return agentCliEnvPassthrough == null ? List.of() : agentCliEnvPassthrough;
    }

    /**
     * Resolves the unset case to {@link Tracker}'s own defaults (design D5, D10). Kept as an
     * explicit method for the same PIT record-constructor reason as {@link #defaultInstanceName}.
     */
    private static Tracker defaultTracker(@Nullable Tracker tracker) {
        return tracker == null ? new Tracker(null, null) : tracker;
    }

    /**
     * Resolves the unset case to an empty map (no check provider configured, no connection profile
     * defined) and defends the map's immutability. No key or value is interpreted here: both {@code
     * factory.check} and {@code factory.connections} are open-ended sets of named subsections whose
     * content only the provider's own {@code CheckSubsectionValidator} may grade (FR4, FR5, FR16,
     * design D12 of add-plugin-architecture) — which is why the vendor-shaped {@code Check.Github}
     * record with its both-or-neither constructor is gone from core. Kept as an explicit method for
     * the same PIT record-constructor reason as {@link #defaultInstanceName}.
     */
    private static Map<String, Map<String, Object>> defaultSubsections(
            @Nullable Map<String, Map<String, Object>> sections) {
        if (sections == null) {
            return Map.of();
        }
        var copy = new LinkedHashMap<String, Map<String, Object>>();
        sections.forEach((name, content) -> copy.put(name, content == null ? Map.of() : Map.copyOf(content)));
        return Map.copyOf(copy);
    }

    /**
     * The {@code factory.tracker} abort-backoff policy defaults (design D5, D10): shared across
     * instances via {@code .gnomish/config.yaml}'s {@code tracker.abort-threshold}, but the
     * backoff Duration pair is factory-instance-level tempo, hence bound here rather than on
     * {@link com.github.oinsio.gnomish.domain.pipeline.TrackerConfig}.
     *
     * @param abortBackoffBase the backoff base for a single abort ({@code
     *     factory.tracker.abort-backoff-base}); defaults to {@code 2m} when unset, mirroring
     *     {@link com.github.oinsio.gnomish.app.take.BackoffPolicy#DEFAULT_BASE} (kept as a
     *     separate literal here — {@code app.take} may not be referenced from this root package,
     *     process-invariants module-boundary rule — so the two defaults must be kept in sync by
     *     hand)
     * @param abortBackoffCap the maximum backoff delay ({@code factory.tracker.abort-backoff-cap});
     *     defaults to {@code 1h} when unset, mirroring {@link
     *     com.github.oinsio.gnomish.app.take.BackoffPolicy#DEFAULT_CAP} (same hand-sync note)
     */
    public record Tracker(Duration abortBackoffBase, Duration abortBackoffCap) {

        private static final Duration DEFAULT_ABORT_BACKOFF_BASE = Duration.ofMinutes(2);
        private static final Duration DEFAULT_ABORT_BACKOFF_CAP = Duration.ofHours(1);

        public Tracker(@Nullable Duration abortBackoffBase, @Nullable Duration abortBackoffCap) {
            this.abortBackoffBase = abortBackoffBase == null ? DEFAULT_ABORT_BACKOFF_BASE : abortBackoffBase;
            this.abortBackoffCap = abortBackoffCap == null ? DEFAULT_ABORT_BACKOFF_CAP : abortBackoffCap;
        }
    }
}
