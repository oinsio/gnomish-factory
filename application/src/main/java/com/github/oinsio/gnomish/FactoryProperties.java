package com.github.oinsio.gnomish;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
 * @param agentCliEnvPassthrough superseded and ignored: the operator passthrough of the layered
 *     child-environment allowlist is {@code factory.sandbox.env-passthrough} (D6, FR9 of
 *     add-sandbox-core). This knob was documentation-only under the replaced
 *     inherit-everything-minus-scrub behavior and is kept solely so existing configs still bind;
 *     defaults to an empty list when unset
 * @param tracker the tracker abort-backoff policy defaults ({@code factory.tracker.*}); defaults
 *     to {@link Tracker#Tracker(Duration, Duration)}'s own defaults when unset
 * @param check the external-check adapter bindings ({@code factory.check.*}); defaults to the
 *     all-unset section (no platform adapter configured) when absent
 */
@ConfigurationProperties("factory")
public record FactoryProperties(
        String instanceName, String agentCliBinary, List<String> agentCliEnvPassthrough, Tracker tracker, Check check) {

    private static final String DEFAULT_INSTANCE_NAME = "gnomish-factory";
    private static final String DEFAULT_AGENT_CLI_BINARY = "claude";

    // The tracker/agentCliEnvPassthrough null-checks below are real, not IDE dead-code noise:
    // Spring's reflective constructor binding can pass null for these record components despite
    // the compile-time @NullMarked contract, and FactoryPropertiesSpec constructs this record
    // with an explicit null for both to exercise exactly that path.
    public FactoryProperties(
            @Nullable String instanceName,
            @Nullable String agentCliBinary,
            @Nullable List<String> agentCliEnvPassthrough,
            @Nullable Tracker tracker,
            @Nullable Check check) {
        this.instanceName = defaultInstanceName(instanceName);
        this.agentCliBinary = defaultAgentCliBinary(agentCliBinary);
        this.agentCliEnvPassthrough = defaultEnvPassthrough(agentCliEnvPassthrough);
        this.tracker = defaultTracker(tracker);
        this.check = defaultCheck(check);
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
     * Resolves the unset case to the all-unset check section (no platform external-check adapter
     * configured). Kept as an explicit method for the same PIT record-constructor reason as
     * {@link #defaultInstanceName}.
     */
    private static Check defaultCheck(@Nullable Check check) {
        return check == null ? new Check(null) : check;
    }

    /**
     * The {@code factory.check} external-check adapter bindings (FR26 of add-sandbox-core):
     * installation-level configuration enabling a platform external-check adapter with config
     * alone — the stage manifests keep declaring only the check itself.
     *
     * @param github the GitHub Actions adapter binding ({@code factory.check.github.*}), or the
     *     all-unset section when absent
     */
    public record Check(Github github) {

        public Check(@Nullable Github github) {
            this.github = github == null ? new Github(null, null) : github;
        }

        /**
         * The GitHub Actions external-check adapter binding (FR26 of add-sandbox-core): the
         * adapter is constructed if and only if both keys are set; setting exactly one is a
         * configuration mistake rejected at bind time, never a silently disabled adapter. The
         * token is never configured here — it resolves by name ({@code
         * GNOMISH_GITHUB_ACTIONS_TOKEN}) through the {@code SecretsProvider} at wiring time.
         *
         * @param apiUrl the platform API base URL ({@code factory.check.github.api-url}), or
         *     {@code null} when the adapter is not configured
         * @param repo the {@code owner/name} repository the checks run in ({@code
         *     factory.check.github.repo}), or {@code null} when the adapter is not configured
         */
        public record Github(
                @Nullable String apiUrl, @Nullable String repo) {

            public Github {
                if ((apiUrl == null) != (repo == null)) {
                    throw new IllegalArgumentException(
                            "factory.check.github requires both api-url and repo (or neither); got only "
                                    + (apiUrl != null ? "api-url" : "repo"));
                }
            }

            /**
             * True when both keys are set and the adapter should be constructed (FR26).
             *
             * <p>PIT M4 documented exception (build.gradle has the full rationale):
             * {@code @DoNotMutate} because PIT's Gregor engine crashes its own minion JVM
             * (RUN_ERROR, not a real test gap) mutating some bytecode shapes of methods on
             * nested {@code record} classes on JDK 17+ (hcoles/pitest#1285, a JVMTI
             * RedefineClasses restriction on NestHost/NestMembers/Record attributes): the
             * {@code BOOLEAN_TRUE_RETURN} mutant of this method died as RUN_ERROR while its
             * {@code BOOLEAN_FALSE_RETURN} sibling was killed normally. Both behavior legs
             * stay covered by FactoryPropertiesSpec ("check section defaults to an
             * unconfigured github binding" and "a fully configured github check binding
             * exposes both keys").
             */
            @DoNotMutate
            public boolean configured() {
                return apiUrl != null;
            }
        }
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
