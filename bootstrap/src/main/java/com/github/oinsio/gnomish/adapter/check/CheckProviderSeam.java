package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.app.CheckClientFactory;
import com.github.oinsio.gnomish.app.ConnectionProfiles;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The seam around the provider-owned {@code factory.check.<provider>} operator subsections (FR4,
 * FR5, FR17, design D11/D12 of add-plugin-architecture) — the check-port counterpart of {@code
 * TrackerSeamValidator}. Core owns the problems <em>around</em> the delegation (a subsection naming
 * a provider nobody discovered) and hands present subsections to the validator the provider itself
 * exposes; it never interprets a provider's keys.
 *
 * <p>Both operations here are pure functions of (registry, configured subsections), which is what
 * lets the composition root call them at startup and a spec call them with a hand-built registry.
 *
 * <p>Implements FR4, FR5, FR17 of add-plugin-architecture.
 */
public final class CheckProviderSeam {

    /** The located field prefix every check subsection error is reported under. */
    private static final String WHERE_PREFIX = "factory.check.";

    private CheckProviderSeam() {}

    /**
     * Validates every configured check subsection, aggregating located errors in provider-name
     * order rather than aborting on the first — one startup report shows an operator every
     * malformed provider at once (NFR-R1).
     *
     * @param file the operator configuration file the subsections were bound from
     * @param configured the {@code factory.check} subsections keyed by provider; never null
     * @param registry the discovered check providers keyed by discriminator; never null
     * @return every located problem, in provider-name order; empty when all subsections are valid
     */
    public static List<ConfigError> validate(
            String file, Map<String, Map<String, Object>> configured, Map<String, CheckClientFactory> registry) {
        return validate(file, configured, registry, ConnectionProfiles.none());
    }

    /**
     * The connection-aware form (FR16, design D8/D12): identical, except that a subsection may
     * reference a named connection profile as {@code connection: <name>} instead of inlining its
     * endpoint and credential-name keys. Core grades the reference itself — malformed, undefined, or
     * declared alongside an inline key the profile also carries — and hands the provider's own
     * validator the profiles, so the provider grades the connection data the reference resolves to.
     *
     * @param file the operator configuration file the subsections were bound from
     * @param configured the {@code factory.check} subsections keyed by provider; never null
     * @param registry the discovered check providers keyed by discriminator; never null
     * @param profiles the operator-declared {@code factory.connections} profiles; never null
     * @return every located problem, in provider-name order; empty when all subsections are valid
     */
    public static List<ConfigError> validate(
            String file,
            Map<String, Map<String, Object>> configured,
            Map<String, CheckClientFactory> registry,
            ConnectionProfiles profiles) {
        List<ConfigError> errors = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : new TreeMap<>(configured).entrySet()) {
            String provider = entry.getKey();
            String where = WHERE_PREFIX + provider;
            CheckClientFactory factory = registry.get(provider);
            if (factory == null) {
                errors.add(new ConfigError(
                        file,
                        where,
                        "unknown check provider '%s'; discovered providers: %s"
                                .formatted(provider, registry.keySet())));
                continue;
            }
            errors.addAll(profiles.validateReference(file, where, entry.getValue()));
            factory.subsectionValidator()
                    .ifPresent(validator -> errors.addAll(validator.validate(file, where, entry.getValue(), profiles)));
        }
        return List.copyOf(errors);
    }

    /**
     * The subsections a provider is actually built from: each configured one with its {@code
     * connection: <name>} reference resolved against the operator's profiles (FR16, design D8), so a
     * provider reads endpoint and credential-name keys in the one inline shape it already knows.
     *
     * <p>Every consumer of the operator's check configuration — client construction and the
     * credential declaration alike — goes through here, which is what makes a profile-resolved
     * credential name reach the scrub set exactly as an inline one does (FR17).
     *
     * @param configured the {@code factory.check} subsections keyed by provider; never null
     * @param profiles the operator-declared {@code factory.connections} profiles; never null
     * @return the resolved subsections, keyed by provider as given; never null
     */
    public static Map<String, Map<String, Object>> resolve(
            Map<String, Map<String, Object>> configured, ConnectionProfiles profiles) {
        var resolved = new LinkedHashMap<String, Map<String, Object>>();
        configured.forEach((provider, subsection) -> resolved.put(provider, profiles.resolve(subsection)));
        return Map.copyOf(resolved);
    }

    /**
     * The credential environment variable names the <em>manifest's own</em> {@code external} checks
     * resolve to (FR11, FR17, design D11): each check's provider is asked what its {@code params}
     * name, and the union joins the connection-declared names of {@link #credentialEnvVars} in the
     * run's scrub / never-allowlist set.
     *
     * <p>This half exists for the built-in {@code http} provider, whose targets are arbitrary
     * endpoints and whose credentials are therefore named per check rather than once per connection.
     * Core still names nothing: the provider is asked, over params core never interprets.
     *
     * <p>A check naming an undiscovered provider contributes nothing — the load seam has already
     * turned that into a located error, so no credential of a provider actually in use is missed.
     *
     * @param definition the loaded pipeline whose stages' checks are asked; never null
     * @param registry the discovered check providers keyed by discriminator; never null
     * @return the declared credential names in stage-then-check order, without duplicates
     */
    public static List<String> checkCredentialEnvVars(
            PipelineDefinition definition, Map<String, CheckClientFactory> registry) {
        var names = new LinkedHashSet<String>();
        for (StageDefinition stage : definition.stages()) {
            for (VerifyCheck check : stage.verify()) {
                if (check instanceof VerifyCheck.External external) {
                    CheckClientFactory factory = registry.get(external.provider());
                    if (factory != null) {
                        names.addAll(factory.checkCredentialEnvVars(external.params()));
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * The credential environment variable names the configured check providers declare (FR17,
     * design D11): the union of each selected provider's own {@link
     * CheckClientFactory#credentialEnvVars} over its resolved subsection. The composition root adds
     * this to the active tracker adapter's declaration and derives the run's scrub /
     * never-allowlist set from that union alone — no core source names a vendor credential
     * constant.
     *
     * <p>A subsection naming an undiscovered provider contributes nothing here; {@link #validate}
     * has already turned it into a startup error, so this never silently under-scrubs a provider
     * that is actually in use.
     *
     * @param configured the {@code factory.check} subsections keyed by provider; never null
     * @param registry the discovered check providers keyed by discriminator; never null
     * @return the declared credential names in provider-name order, without duplicates; never null
     */
    public static List<String> credentialEnvVars(
            Map<String, Map<String, Object>> configured, Map<String, CheckClientFactory> registry) {
        var names = new LinkedHashSet<String>();
        for (Map.Entry<String, Map<String, Object>> entry : new TreeMap<>(configured).entrySet()) {
            CheckClientFactory factory = registry.get(entry.getKey());
            if (factory != null) {
                names.addAll(factory.credentialEnvVars(entry.getValue()));
            }
        }
        return List.copyOf(names);
    }
}
