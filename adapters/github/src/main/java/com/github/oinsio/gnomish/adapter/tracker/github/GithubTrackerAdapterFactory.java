package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.app.TrackerAdapterFactory;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles a live, ready-to-use {@link GithubTracker} from a validated {@link TrackerConfig}
 * (task 5.15): reads {@code GNOMISH_GITHUB_TOKEN} from the environment (NFR-S1 — never from
 * yaml), resolves the four label definitions (configured value or FR5 default), provisions
 * labels as a startup smoke test (NFR-R4) BEFORE constructing any {@code Tracker}-facing
 * collaborator, then wires the six concern-split collaborators over one shared {@link
 * GithubHttpClient}/{@link GithubConditionalRequestCache}/{@link GithubLabelOps}, exactly
 * mirroring {@code GithubTrackerContractSpec}'s production assembly.
 *
 * <p>Label defaults (FR5): names {@code gnomish:ready}/{@code working}/{@code needs-human}/{@code
 * delivered}; colors from GitHub's common palette — {@code 2ea44f}/{@code 1f6feb}/{@code
 * d73a4a}/{@code 8250df}; each with a short operator-hint description. Configured {@code labels.*}
 * entries (schema-validated by {@link GithubLabelsValidator}) override the default for that state.
 *
 * <p>Implements FR5, FR9, FR17, NFR-R4, NFR-S1 of add-tracker-port.
 */
public record GithubTrackerAdapterFactory(SecretsProvider secretsProvider) implements TrackerAdapterFactory {

    /** {@code GNOMISH_GITHUB_TOKEN}: the GitHub tracker credential env var (design D5, NFR-S1). */
    public static final String TOKEN_ENV_VAR = "GNOMISH_GITHUB_TOKEN";

    /**
     * @param secretsProvider the seam through which {@code GNOMISH_GITHUB_TOKEN} is resolved by name
     *     (FR18, NFR-S1 of add-sandbox-core); never null — the composition root injects the
     *     installation-configured adapter, and tests a fake
     */
    public GithubTrackerAdapterFactory {}

    // PIT M4 documented exception: @DoNotMutate — the token now resolves through the injected
    // SecretsProvider (the missing-token throw is covered by GithubTrackerAdapterFactorySpec with an
    // empty provider), but this method's success path drives the WireMock-backed assembly of the
    // 3-arg create(...) seam (label provisioning, collaborator wiring) — an integration boundary the
    // spec exercises through that seam directly.
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
        String apiUrl = GithubTrackerAdapterFactoryLabels.requireStringValue(subsection, "api-url");
        GithubRepoRef repoRef =
                GithubRepoRef.parse(GithubTrackerAdapterFactoryLabels.requireStringValue(subsection, "repo"));
        String owner = repoRef.owner();
        String repo = repoRef.repo();

        GithubLabelDef readyLabel = GithubTrackerAdapterFactoryLabels.resolveLabel(
                subsection, "ready", GithubTrackerAdapterFactoryLabels.DEFAULT_READY);
        GithubLabelDef workingLabel = GithubTrackerAdapterFactoryLabels.resolveLabel(
                subsection, "working", GithubTrackerAdapterFactoryLabels.DEFAULT_WORKING);
        GithubLabelDef needsHumanLabel = GithubTrackerAdapterFactoryLabels.resolveLabel(
                subsection, "needs-human", GithubTrackerAdapterFactoryLabels.DEFAULT_NEEDS_HUMAN);
        GithubLabelDef deliveredLabel = GithubTrackerAdapterFactoryLabels.resolveLabel(
                subsection, "delivered", GithubTrackerAdapterFactoryLabels.DEFAULT_DELIVERED);

        var httpClient = new GithubHttpClient(apiUrl, token);
        var cache = new GithubConditionalRequestCache(httpClient);
        var labelOps = new GithubLabelOps(httpClient);

        new GithubLabelProvisioner(httpClient)
                .provision(owner, repo, List.of(readyLabel, workingLabel, needsHumanLabel, deliveredLabel));

        return new GithubTracker(
                new GithubFeedQuery(cache, owner, repo, readyLabel.name()),
                new GithubTaskFetcher(cache, workingLabel.name(), needsHumanLabel.name(), deliveredLabel.name()),
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
                new GithubDecisions(httpClient, instanceId),
                new GithubHeartbeat(httpClient, instanceId),
                new GithubOpenQuery(cache, owner, repo, workingLabel.name(), needsHumanLabel.name()),
                new GithubStaleClaimRemoval(httpClient, labelOps, instanceId, workingLabel.name(), readyLabel.name()));
    }

    @Override
    public TaskRef expandRef(TrackerConfig config, String rawRef) {
        int issueNumber = Integer.parseInt(rawRef.startsWith("#") ? rawRef.substring(1) : rawRef);
        return GithubRefExpander.expand(config.subsection(), issueNumber);
    }

    // PIT M4 documented exception (same integration-boundary rationale as create(config, id)): this
    // entry point resolves GNOMISH_GITHUB_TOKEN through the SecretsProvider and delegates. The
    // foreign-repo logic (owner/repo threading, verify delegation, exception→refusal translation) is
    // fully covered via the explicit-token testing seam below.
    @DoNotMutate
    @Override
    public Optional<String> refuseForeignRef(TrackerConfig config, TaskRef ref) {
        return refuseForeignRef(config, ref, requireToken());
    }

    /**
     * Package-private testing seam mirroring {@link #create(TrackerConfig, String, String)}:
     * verifies {@code ref} against an explicit {@code token} instead of the environment. Delegates to
     * {@link GithubForeignRepoCheck} (design D8), which issues a {@code GET /repos/{owner}/{repo}}
     * only when the ref's owner/repo differ from the configured binding — a matching id returns empty
     * with no network call. A foreign id (or one whose rename redirect resolves elsewhere) is
     * translated from {@link GithubForeignRepoException} into the port's refusal message.
     */
    Optional<String> refuseForeignRef(TrackerConfig config, TaskRef ref, String token) {
        Map<String, Object> subsection = config.subsection();
        String apiUrl = GithubTrackerAdapterFactoryLabels.requireStringValue(subsection, "api-url");
        GithubRepoRef repoRef =
                GithubRepoRef.parse(GithubTrackerAdapterFactoryLabels.requireStringValue(subsection, "repo"));
        var check = new GithubForeignRepoCheck(new GithubHttpClient(apiUrl, token));
        try {
            check.verify(GithubTaskId.parse(ref.id()), repoRef.owner(), repoRef.repo());
            return Optional.empty();
        } catch (GithubForeignRepoException e) {
            return Optional.of(refusalMessageOf(e));
        }
    }

    // PIT M4 documented exception (mirrors TakeEngineExecution#reasonFor): @DoNotMutate — the null
    // branch is provably unreachable, GithubForeignRepoException's every constructor calls
    // super(String) with a non-null message; it exists only to satisfy NullAway's @Nullable view of
    // Throwable.getMessage(). Isolated to its own method so this dead-but-defensive branch gives no
    // mutant a place to survive against the refusal logic GithubForeignRepoCheckSpec already covers.
    @DoNotMutate
    private static String refusalMessageOf(GithubForeignRepoException e) {
        String message = e.getMessage();
        return message == null ? "foreign-repo refusal" : message;
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

    /**
     * Resolves {@code GNOMISH_GITHUB_TOKEN} through the {@link SecretsProvider} (FR18, NFR-S1 of
     * add-sandbox-core), failing closed with a clear {@link GithubTrackerConfigException} when it is
     * absent or blank — the provider's {@link SecretsProvider#find} already treats blank as absent,
     * so there is no silent empty value. The token is never logged.
     *
     * <p>PIT M4 documented exception: {@code @DoNotMutate} — reachable only from the two
     * {@code @DoNotMutate} entry points ({@link #create(TrackerConfig, String)} and {@link
     * #refuseForeignRef(TrackerConfig, TaskRef)}); the missing-token throw is covered behaviorally
     * by GithubTrackerAdapterFactorySpec with an empty provider, while a resolved token flows into
     * the WireMock-backed assembly of those entry points — an integration boundary.
     */
    @DoNotMutate
    private String requireToken() {
        return secretsProvider
                .find(TOKEN_ENV_VAR)
                .orElseThrow(() -> new GithubTrackerConfigException(
                        TOKEN_ENV_VAR + " is required to use the GitHub tracker adapter, but is missing or blank"));
    }
}
