package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.app.CheckClientFactory;
import com.github.oinsio.gnomish.app.CheckParamsValidator;
import com.github.oinsio.gnomish.app.CheckRunContext;
import com.github.oinsio.gnomish.app.CheckSubsectionValidator;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The built-in {@code http} check provider (FR9, design D4 of add-plugin-architecture): the
 * escape-hatch that points a stage at a third-party CI or quality service's REST endpoint without
 * writing an adapter, symmetric to the {@code command} check for local verification.
 *
 * <p>It ships in core, but core gives it nothing extra: the {@code META-INF/services} entry in this
 * module is what makes it selectable, it is keyed in the same registry by the same {@code
 * ServiceLoader} pass as github or a third party's jar, and it is reached through the same
 * dispatching composite. "Built-in" here is packaging, not privilege (FR1).
 *
 * <p>It contributes no pin paths (FR15): there is no repo-side definition file an arbitrary REST
 * endpoint could be pinned to, so only a check's law-declared {@code pinPaths} pin it — and a check
 * declaring none passes the pin guard vacuously, per the empty-union rule.
 *
 * <p>Its credentials are unusual in one way, handled by {@link #checkCredentialEnvVars}: because the
 * provider serves arbitrary endpoints, each check names its own credential in the manifest rather
 * than inheriting one from a connection subsection (FR11, design D11).
 *
 * <p>Implements FR9, FR10, FR11, FR15, FR17 of add-plugin-architecture.
 */
public final class HttpCheckClientFactory implements CheckClientFactory {

    /** {@code factory.check.http} — this provider's discovery discriminator (FR5, FR9). */
    public static final String PROVIDER = "http";

    /** Public and no-arg, as {@code ServiceLoader} instantiation requires (FR2, design D2). */
    public HttpCheckClientFactory() {}

    @Override
    public String provider() {
        return PROVIDER;
    }

    /**
     * Builds the client over the production JDK exchange, guarded by the operator's egress allowlist
     * — the {@code subsection}'s one and only content (NFR-S2, design D5). Without a run context the
     * client supplies no interpolation values, so any check that interpolates fails closed.
     */
    @Override
    public ExternalCheckClient create(SecretsProvider secrets, Map<String, Object> subsection) {
        return create(secrets, subsection, CheckRunContext.none());
    }

    /**
     * The run-aware form the composition root calls: same guarded exchange, plus the run's
     * whitelisted variables an http check may address its own result with (NFR-S2).
     */
    @Override
    public ExternalCheckClient create(
            SecretsProvider secrets, Map<String, Object> subsection, CheckRunContext runContext) {
        var guarded = new GuardedHttpCheckExchange(new JdkHttpCheckExchange(), EgressAllowlist.from(subsection));
        return new HttpExternalCheckClient(guarded, secrets, runContext);
    }

    /** Grades a check's target, auth and conditions at the load seam (FR6). */
    @Override
    public Optional<CheckParamsValidator> paramsValidator() {
        return Optional.of(new HttpCheckParamsValidator());
    }

    /** Grades the operator subsection, which is correct exactly when it is empty (FR4). */
    @Override
    public Optional<CheckSubsectionValidator> subsectionValidator() {
        return Optional.of(new HttpCheckSubsectionValidator());
    }

    /**
     * The credential this one check names, if any (FR11, FR17, design D11) — the per-check half of
     * the credential declaration, which the composition root unions into the run's scrub and
     * never-allowlist set so a manifest-named secret is treated exactly like a vendor plugin's.
     */
    @Override
    public List<String> checkCredentialEnvVars(Map<String, Object> params) {
        HttpCheckParams.Auth auth = HttpCheckParams.from(params).auth();
        return auth == null ? List.of() : List.of(auth.credential());
    }
}
