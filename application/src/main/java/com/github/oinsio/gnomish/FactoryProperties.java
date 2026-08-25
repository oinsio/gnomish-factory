package com.github.oinsio.gnomish;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Immutable typed configuration of a factory instance, bound from the {@code factory.*} external
 * properties via constructor binding (design D4). Validation is plain Java in the
 * {@link FactoryPropertyDefaults} companion, triggered by the canonical constructor — directly
 * spec-able and mutation-testable, no Bean Validation.
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
 * @param gitNetworkTimeout the hard bound on a git command that reaches a remote — {@code fetch},
 *     {@code push}, {@code ls-remote}, {@code clone}, {@code remote update} ({@code
 *     factory.git-network-timeout}); defaults to 5 minutes when unset, rejected when non-positive
 *     (FR5, UX1, design D8 of bound-subprocess-commands); local git commands stay unbounded
 * @param dockerCommandTimeout the hard bound on one {@code docker} management command ({@code
 *     factory.docker-command-timeout}); defaults to 5 minutes when unset, rejected when
 *     non-positive (FR5, UX1, design D8 of bound-subprocess-commands)
 * @param checkCommandTimeout the hard bound on one {@code command} check ({@code
 *     factory.check-command-timeout}); defaults to 30 minutes when unset, rejected when
 *     non-positive — expiry is a quality failure carrying the captured tail (FR5, FR12, UX1,
 *     design D8, D12 of bound-subprocess-commands)
 */
@ConfigurationProperties("factory")
public record FactoryProperties(
        String instanceName,
        String agentCliBinary,
        Duration agentCliTailDrainGrace,
        List<String> agentCliEnvPassthrough,
        Tracker tracker,
        Map<String, Map<String, Object>> check,
        Map<String, Map<String, Object>> connections,
        Duration gitNetworkTimeout,
        Duration dockerCommandTimeout,
        Duration checkCommandTimeout) {

    // Every component is resolved through FactoryPropertyDefaults rather than inline: Spring's
    // reflective constructor binding can pass null for any component despite the compile-time
    // @NullMarked contract (FactoryPropertiesSpec constructs the record with explicit nulls to
    // exercise exactly that path), and PIT's record filter would suppress mutations of logic
    // inlined into a record constructor — the companion keeps the validation in the mutation scope.
    // @ConstructorBinding is required, not decorative: the convenience constructors below make this
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
            @Nullable Map<String, Map<String, Object>> connections,
            @Nullable Duration gitNetworkTimeout,
            @Nullable Duration dockerCommandTimeout,
            @Nullable Duration checkCommandTimeout) {
        this.instanceName = FactoryPropertyDefaults.instanceName(instanceName);
        this.agentCliBinary = FactoryPropertyDefaults.agentCliBinary(agentCliBinary);
        this.agentCliTailDrainGrace = FactoryPropertyDefaults.tailDrainGrace(agentCliTailDrainGrace);
        this.agentCliEnvPassthrough = FactoryPropertyDefaults.envPassthrough(agentCliEnvPassthrough);
        this.tracker = FactoryPropertyDefaults.tracker(tracker);
        this.check = FactoryPropertyDefaults.subsections(check);
        this.connections = FactoryPropertyDefaults.subsections(connections);
        this.gitNetworkTimeout = FactoryPropertyDefaults.positiveTimeout(
                gitNetworkTimeout, FactoryPropertyDefaults.DEFAULT_GIT_NETWORK_TIMEOUT, "factory.git-network-timeout");
        this.dockerCommandTimeout = FactoryPropertyDefaults.positiveTimeout(
                dockerCommandTimeout,
                FactoryPropertyDefaults.DEFAULT_DOCKER_COMMAND_TIMEOUT,
                "factory.docker-command-timeout");
        this.checkCommandTimeout = FactoryPropertyDefaults.positiveTimeout(
                checkCommandTimeout,
                FactoryPropertyDefaults.DEFAULT_CHECK_COMMAND_TIMEOUT,
                "factory.check-command-timeout");
    }

    /**
     * Convenience for the callers predating the three subprocess deadlines (FR5, design D8 of
     * bound-subprocess-commands): each deadline keeps its documented default. Property binding
     * ignores this one too — see the note on {@code @ConstructorBinding} above.
     */
    public FactoryProperties(
            @Nullable String instanceName,
            @Nullable String agentCliBinary,
            @Nullable Duration agentCliTailDrainGrace,
            @Nullable List<String> agentCliEnvPassthrough,
            @Nullable Tracker tracker,
            @Nullable Map<String, Map<String, Object>> check,
            @Nullable Map<String, Map<String, Object>> connections) {
        this(
                instanceName,
                agentCliBinary,
                agentCliTailDrainGrace,
                agentCliEnvPassthrough,
                tracker,
                check,
                connections,
                null,
                null,
                null);
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
