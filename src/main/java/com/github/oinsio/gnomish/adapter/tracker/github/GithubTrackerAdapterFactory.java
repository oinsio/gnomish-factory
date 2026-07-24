package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.TrackerAdapterFactory;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.List;
import java.util.Map;

/**
 * Assembles a live, ready-to-use {@link GithubTracker} from a validated {@link TrackerConfig}
 * (task 5.15): reads {@code GNOMISH_GITHUB_TOKEN} from the environment (NFR-S1 — never from
 * yaml), resolves the four label definitions (configured value or FR5 default), provisions
 * labels as a startup smoke test (NFR-R4) BEFORE constructing any {@code Tracker}-facing
 * collaborator, then wires the six concern-split collaborators over one shared {@link
 * GithubHttpClient}/{@link GithubConditionalRequestCache}/{@link GithubLabelOps}, exactly
 * mirroring {@code GithubTrackerContractSpec}'s production assembly.
 *
 * <p>Label defaults (FR5): names {@code gnomish:ready}/{@code gnomish:working}/{@code
 * gnomish:needs-human}/{@code gnomish:delivered}; colors from GitHub's own commonly-used label
 * palette — {@code 2ea44f} (green), {@code 0366d6} (blue), {@code d73a4a} (red), {@code 6f42c1}
 * (purple); each with a short operator-hint description. Configured {@code labels.*} entries
 * (already schema-validated by {@link GithubLabelsValidator}) override the default for that
 * logical state only.
 *
 * <p>Implements FR5, FR9, FR17, NFR-R4, NFR-S1 of add-tracker-port.
 */
public final class GithubTrackerAdapterFactory implements TrackerAdapterFactory {

    /** {@code GNOMISH_GITHUB_TOKEN}: the GitHub tracker credential env var (design D5, NFR-S1). */
    public static final String TOKEN_ENV_VAR = "GNOMISH_GITHUB_TOKEN";

    private static final GithubLabelDef DEFAULT_READY =
            new GithubLabelDef("gnomish:ready", "2ea44f", "Gnomish factory: ready to be claimed");
    private static final GithubLabelDef DEFAULT_WORKING =
            new GithubLabelDef("gnomish:working", "0366d6", "Gnomish factory: currently being worked");
    private static final GithubLabelDef DEFAULT_NEEDS_HUMAN =
            new GithubLabelDef("gnomish:needs-human", "d73a4a", "Gnomish factory: waiting on a human decision");
    private static final GithubLabelDef DEFAULT_DELIVERED =
            new GithubLabelDef("gnomish:delivered", "6f42c1", "Gnomish factory: delivered for review");

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate — this
    // method's success path (the token resolves and construction proceeds) can only be exercised
    // by a test where GNOMISH_GITHUB_TOKEN is genuinely present in the JVM's real process
    // environment, which — per this class's own Javadoc above — is "not reliably possible on
    // module-path JVMs without --add-opens"; GithubTrackerAdapterFactorySpec instead covers the
    // 3-arg create(...) overload's success path directly (the actual assembly logic under test),
    // and covers this method's failure path (missing token) via the real environment, which the
    // "missing GNOMISH_GITHUB_TOKEN" scenario asserts only runs meaningfully when the real
    // environment has no token set. A genuine integration boundary, not a coverage shortcut.
    @DoNotMutate
    @Override
    public Tracker create(TrackerConfig config, String instanceId) {
        return create(config, instanceId, requireToken());
    }

    /**
     * Package-private testing seam: builds the tracker from an explicit {@code token} instead of
     * reading {@code GNOMISH_GITHUB_TOKEN} from the environment, so tests can exercise the
     * assembly (label provisioning, collaborator wiring) against WireMock without mutating the
     * real process environment (not reliably possible on module-path JVMs without {@code
     * --add-opens}). The public {@link #create(TrackerConfig, String)} is the only production
     * entry point and always resolves the token from the environment (NFR-S1).
     */
    Tracker create(TrackerConfig config, String instanceId, String token) {
        Map<String, Object> subsection = config.subsection();
        String apiUrl = requireStringValue(subsection, "api-url");
        GithubRepoRef repoRef = GithubRepoRef.parse(requireStringValue(subsection, "repo"));
        String owner = repoRef.owner();
        String repo = repoRef.repo();

        GithubLabelDef readyLabel = resolveLabel(subsection, "ready", DEFAULT_READY);
        GithubLabelDef workingLabel = resolveLabel(subsection, "working", DEFAULT_WORKING);
        GithubLabelDef needsHumanLabel = resolveLabel(subsection, "needs-human", DEFAULT_NEEDS_HUMAN);
        GithubLabelDef deliveredLabel = resolveLabel(subsection, "delivered", DEFAULT_DELIVERED);

        var httpClient = new GithubHttpClient(apiUrl, token);
        var cache = new GithubConditionalRequestCache(httpClient);
        var labelOps = new GithubLabelOps(httpClient);

        new GithubLabelProvisioner(httpClient)
                .provision(owner, repo, List.of(readyLabel, workingLabel, needsHumanLabel, deliveredLabel));

        return new GithubTracker(
                new GithubFeedQuery(cache, owner, repo, readyLabel.name()),
                new GithubTaskFetcher(httpClient, workingLabel.name(), needsHumanLabel.name()),
                new GithubClaimLease(httpClient, labelOps, readyLabel.name(), workingLabel.name()),
                new GithubStateWrites(
                        httpClient,
                        labelOps,
                        instanceId,
                        workingLabel.name(),
                        needsHumanLabel.name(),
                        deliveredLabel.name(),
                        readyLabel.name()),
                new GithubCorrespondence(httpClient, instanceId),
                new GithubDecisions(httpClient, instanceId));
    }

    @Override
    public TaskRef expandRef(TrackerConfig config, String rawRef) {
        int issueNumber = Integer.parseInt(rawRef.startsWith("#") ? rawRef.substring(1) : rawRef);
        return GithubRefExpander.expand(config.subsection(), issueNumber);
    }

    /**
     * Declares {@code GNOMISH_GITHUB_TOKEN} as this adapter's sole credential environment
     * variable (design D17, NFR-S1): the agent process launcher scrubs it from the gnome's CLI
     * subprocess environment regardless of {@code agent-cli-env-passthrough}.
     */
    @Override
    public List<String> credentialEnvVars() {
        return List.of(TOKEN_ENV_VAR);
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate — the
    // null-check half of the guard is covered by GithubTrackerAdapterFactorySpec's
    // "missing GNOMISH_GITHUB_TOKEN" scenario (a real, unset environment), but the blank-but-
    // present half (token.isBlank()) and the success return can only be exercised by setting
    // GNOMISH_GITHUB_TOKEN to a real value in the JVM's process environment — not reliably
    // possible on a module-path JVM without --add-opens (see this class's own Javadoc). A genuine
    // integration boundary over the real OS environment, not a coverage shortcut; the 3-arg
    // create(...) overload (the actual construction logic that matters) is fully covered via its
    // own explicit-token testing seam.
    @DoNotMutate
    private static String requireToken() {
        String token = System.getenv(TOKEN_ENV_VAR);
        if (token == null || token.isBlank()) {
            throw new GithubTrackerConfigException(
                    TOKEN_ENV_VAR + " environment variable is required to use the GitHub tracker adapter, but is"
                            + " missing or blank");
        }
        return token;
    }

    private static String requireStringValue(Map<String, Object> subsection, String key) {
        Object value = subsection.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new GithubTrackerConfigException(
                    "tracker.github." + key + " is required to build the GitHub tracker");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static GithubLabelDef resolveLabel(Map<String, Object> subsection, String key, GithubLabelDef fallback) {
        Object labels = subsection.get("labels");
        if (!(labels instanceof Map<?, ?> labelsMap)) {
            return fallback;
        }
        Object entry = labelsMap.get(key);
        if (!(entry instanceof Map<?, ?> raw)) {
            return fallback;
        }
        Map<String, Object> entryMap = (Map<String, Object>) raw;
        String name = (String) entryMap.getOrDefault("name", fallback.name());
        String color = (String) entryMap.getOrDefault("color", fallback.color());
        return new GithubLabelDef(name, color, fallback.description());
    }
}
