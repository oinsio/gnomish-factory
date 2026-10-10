package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.adapter.github.GithubCredential;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.app.TrackerAdapterContext;
import com.github.oinsio.gnomish.app.TrackerAdapterFactory;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * <p>Discovered through {@code ServiceLoader} (FR1, FR12 of add-plugin-architecture): a public
 * no-arg constructor, with the {@link SecretsProvider} — and every other host collaborator, the
 * time equipment included — arriving on the {@link TrackerAdapterContext} its one {@code create}
 * receives rather than in the constructor (FR2, design D2; FR23, design D21 of
 * supervise-daemon-loops-and-embed-dashboard).
 *
 * <p>Implements FR5, FR9, FR17, NFR-R4, NFR-S1 of add-tracker-port; FR1, FR2, FR4, FR17 of
 * add-plugin-architecture; FR20, FR23 of supervise-daemon-loops-and-embed-dashboard.
 */
public final class GithubTrackerAdapterFactory implements TrackerAdapterFactory {

    /** {@code GNOMISH_GITHUB_TOKEN}: the GitHub tracker credential env var (design D5, NFR-S1). */
    public static final String TOKEN_ENV_VAR = "GNOMISH_GITHUB_TOKEN";

    /** {@code tracker.type: github} — this adapter's discovery discriminator (FR1). */
    public static final String TYPE = "github";

    /** Public and no-arg, as {@code ServiceLoader} instantiation requires (FR2, design D2). */
    public GithubTrackerAdapterFactory() {}

    @Override
    public String type() {
        return TYPE;
    }

    /**
     * The one entry point the composition root calls (FR23, design D21 of
     * supervise-daemon-loops-and-embed-dashboard): everything — the credential, the configuration,
     * the instance id, the tenure record and the time equipment — is read from {@code context}. Every
     * structural marker this adapter writes is stamped with the tenure {@code context.epochs()}
     * reports for the task (FR13 of harden-task-branch-contract), and every instant it stamps reads
     * {@code context.timeEquipment()} — the host's time, virtual under test (FR20) — never a clock
     * this factory builds itself.
     */
    @Override
    public Tracker create(TrackerAdapterContext context) {
        TrackerConfig config = context.config();
        return create(
                config,
                context.instanceId(),
                requireToken(context.secrets(), config),
                context.epochs(),
                context.timeEquipment().clock());
    }

    /**
     * Package-private testing seam: builds the tracker from an explicit {@code token} instead of
     * reading {@code GNOMISH_GITHUB_TOKEN} from the environment, so tests can exercise the
     * assembly (label provisioning, collaborator wiring) against WireMock without mutating the
     * real process environment (not reliably possible on module-path JVMs without {@code
     * --add-opens}). The public {@link #create(TrackerAdapterContext)} is the only production entry
     * point and always resolves the token through the context's secrets seam (NFR-S1).
     */
    Tracker create(TrackerConfig config, String instanceId, String token, InstantSource clock) {
        return create(config, instanceId, token, ClaimEpochSource.NONE, clock);
    }

    /**
     * The full assembly seam: every collaborator that stamps an instant — the claim, marker,
     * heartbeat, decision-ack, stale-claim and index-repair writes — reads it from {@code clock}
     * (FR20 of supervise-daemon-loops-and-embed-dashboard).
     */
    Tracker create(
            TrackerConfig config, String instanceId, String token, ClaimEpochSource epochs, InstantSource clock) {
        Map<String, Object> subsection = config.subsection();
        GithubRepoRef repoRef = GithubTrackerAdapterFactorySupport.requireRepoRef(subsection);
        String owner = repoRef.owner();
        String repo = repoRef.repo();

        GithubLabelDef readyLabel = GithubTrackerAdapterFactorySupport.resolveLabel(
                subsection, "ready", GithubTrackerAdapterFactorySupport.DEFAULT_READY);
        GithubLabelDef workingLabel = GithubTrackerAdapterFactorySupport.resolveLabel(
                subsection, "working", GithubTrackerAdapterFactorySupport.DEFAULT_WORKING);
        GithubLabelDef needsHumanLabel = GithubTrackerAdapterFactorySupport.resolveLabel(
                subsection, "needs-human", GithubTrackerAdapterFactorySupport.DEFAULT_NEEDS_HUMAN);
        GithubLabelDef deliveredLabel = GithubTrackerAdapterFactorySupport.resolveLabel(
                subsection, "delivered", GithubTrackerAdapterFactorySupport.DEFAULT_DELIVERED);

        var httpClient = GithubTrackerAdapterFactorySupport.httpClientFor(subsection, token);
        var cache = new GithubConditionalRequestCache(httpClient);
        var labelOps = new GithubLabelOps(httpClient);
        // One renderer for every GithubMarkerKind (FR11): every structural comment this adapter
        // writes is stamped and upserted through it, so no write path posts blind.
        var markerWriter = new GithubMarkerWriter(new GithubCommentUpsert(httpClient), epochs, instanceId, clock);

        var stateLabels = new GithubStateLabels(
                readyLabel.name(), workingLabel.name(), needsHumanLabel.name(), deliveredLabel.name());

        new GithubLabelProvisioner(httpClient)
                .provision(owner, repo, List.of(readyLabel, workingLabel, needsHumanLabel, deliveredLabel));

        return new GithubTracker(
                new GithubFeedQuery(cache, owner, repo, readyLabel.name()),
                new GithubTaskFetcher(
                        cache,
                        workingLabel.name(),
                        needsHumanLabel.name(),
                        deliveredLabel.name(),
                        GithubDesignatorRules.from(subsection)),
                new GithubClaimLease(httpClient, labelOps, readyLabel.name(), workingLabel.name(), clock),
                new GithubStateWrites(
                        httpClient,
                        labelOps,
                        markerWriter,
                        workingLabel.name(),
                        needsHumanLabel.name(),
                        deliveredLabel.name(),
                        readyLabel.name()),
                new GithubCorrespondence(markerWriter),
                new GithubDecisions(httpClient, markerWriter, clock),
                new GithubHeartbeat(httpClient, instanceId, clock),
                new GithubOpenQuery(cache, owner, repo, stateLabels),
                new GithubStaleClaimRemoval(
                        httpClient, labelOps, markerWriter, workingLabel.name(), readyLabel.name(), clock),
                new GithubIndexRepair(httpClient, labelOps, markerWriter, stateLabels, clock));
    }

    @Override
    public TaskRef expandRef(TrackerConfig config, String rawRef) {
        int issueNumber = Integer.parseInt(rawRef.startsWith("#") ? rawRef.substring(1) : rawRef);
        return GithubRefExpander.expand(config.subsection(), issueNumber);
    }

    // PIT M4 documented exception: @DoNotMutate — this entry point resolves GNOMISH_GITHUB_TOKEN
    // through the SecretsProvider and delegates, holding no decision of its own. The
    // foreign-repo logic (owner/repo threading, verify delegation, exception→refusal translation) is
    // fully covered via the explicit-token testing seam below.
    @DoNotMutate
    @Override
    public Optional<String> refuseForeignRef(SecretsProvider secrets, TrackerConfig config, TaskRef ref) {
        return refuseForeignRef(config, ref, requireToken(secrets, config));
    }

    /**
     * Package-private testing seam mirroring {@link #create(TrackerConfig, String, String, InstantSource)}:
     * verifies {@code ref} against an explicit {@code token} instead of the environment. Delegates to
     * {@link GithubForeignRepoCheck} (design D8), which issues a {@code GET /repos/{owner}/{repo}}
     * only when the ref's owner/repo differ from the configured binding — a matching id returns empty
     * with no network call. A foreign id (or one whose rename redirect resolves elsewhere) is
     * translated from {@link GithubForeignRepoException} into the port's refusal message.
     */
    Optional<String> refuseForeignRef(TrackerConfig config, TaskRef ref, String token) {
        Map<String, Object> subsection = config.subsection();
        GithubRepoRef repoRef = GithubTrackerAdapterFactorySupport.requireRepoRef(subsection);
        var check = new GithubForeignRepoCheck(GithubTrackerAdapterFactorySupport.httpClientFor(subsection, token));
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
     * Declares this adapter's sole credential environment variable (design D17, NFR-S1): the agent
     * process launcher scrubs it from the gnome's CLI subprocess environment regardless of {@code
     * factory.sandbox.env-passthrough}. The name is {@link #TOKEN_ENV_VAR} unless the resolved {@code
     * tracker.github} connection renames it through {@code credential} — which a named connection
     * profile may (FR16, design D8/D11 of add-plugin-architecture) — so a profile-renamed credential
     * is scrubbed exactly like the default one.
     */
    @Override
    public List<String> credentialEnvVars(TrackerConfig config) {
        return List.of(GithubCredential.nameOr(config.subsection(), TOKEN_ENV_VAR));
    }

    /**
     * Declares the designator kinds this adapter extracts (FR3 of add-base-ref-resolution): exactly
     * the keys of the {@code tracker.github.designators} map, and nothing else — a kind with no rule
     * is never extracted, so it is never reported. Core reads this instead of the GitHub keys
     * themselves, and turns "kind {@code base} extracted, {@code task-branch.base.allowed} empty" into a located
     * load error rather than a stream of parked tasks.
     */
    @Override
    public Set<String> configuredDesignatorKinds(TrackerConfig config) {
        return GithubDesignatorRules.from(config.subsection()).kinds();
    }

    /**
     * Exposes {@link GithubTrackerSubsectionValidator} as this adapter's own {@code tracker.github}
     * content validator, so the load seam grades the subsection with the very validator that belongs
     * to the factory later building the live tracker (FR4, design D1/D3 of add-plugin-architecture).
     */
    @Override
    public Optional<TrackerSubsectionValidator> subsectionValidator() {
        return Optional.of(new GithubTrackerSubsectionValidator());
    }

    /**
     * Resolves the connection's credential — {@code GNOMISH_GITHUB_TOKEN} unless a profile renamed
     * it (FR16) — through the {@link SecretsProvider} (FR18, NFR-S1 of
     * add-sandbox-core), failing closed with a clear {@link GithubTrackerConfigException} when it is
     * absent or blank — the provider's {@link SecretsProvider#find} already treats blank as absent,
     * so there is no silent empty value. The token is never logged.
     */
    private String requireToken(SecretsProvider secrets, TrackerConfig config) {
        String credential = GithubCredential.nameOr(config.subsection(), TOKEN_ENV_VAR);
        return secrets.find(credential)
                .orElseThrow(() -> new GithubTrackerConfigException(
                        credential + " is required to use the GitHub tracker adapter, but is missing or blank"));
    }
}
