package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.check.ExternalCheckPinContributor;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Constructs a live {@link ExternalCheckClient} for one registered {@code provider} — the check
 * port's mirror of {@link TrackerAdapterFactory} (design D3 of add-plugin-architecture). Before
 * this SPI existed the check port had no factory interface at all: one concrete vendor class was
 * wired in by the composition root, which made GitHub a special case rather than a provider. Here
 * GitHub is one entry of a {@code Map<provider, CheckClientFactory>} like any other.
 *
 * <p>Implementations are discovered through {@code ServiceLoader} and keyed by {@link #provider()},
 * so they must offer a public no-arg constructor (FR1, FR2, design D1/D2). Every host-provided
 * collaborator reaches them through the one {@link CheckClientContext} their single {@link
 * #create(CheckClientContext)} receives — there is no overload chain to override the wrong link of;
 * a collaborator the host adds later is a new accessor on the context (design D21 of
 * supervise-daemon-loops-and-embed-dashboard).
 *
 * <p>The configuration split follows the port's two sources. Connection data — endpoint,
 * repository, credential name — arrives as the operator-side {@code factory.check.<provider>}
 * subsection, graded by {@link #subsectionValidator()} and handed to {@link #create} on the context. Per-check
 * selectors travel the other way, as the stage manifest's params on the {@code external} check,
 * graded by {@link #paramsValidator()}.
 *
 * <p>Implements FR2, FR4, FR5, FR15, FR17 of add-plugin-architecture; FR23 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
public interface CheckClientFactory {

    /**
     * This provider's {@code provider} discriminator — the key its factory is registered under in
     * the {@code ServiceLoader}-built registry, the name an operator writes as the {@code
     * factory.check.<provider>} subsection, and the value a stage manifest's {@code external} check
     * selects (e.g. {@code "github"}, {@code "http"}).
     *
     * <p>Two discovered factories claiming the same discriminator, or a factory returning a blank
     * one, fail the registry build with a named error at startup rather than at first use (NFR-R1).
     *
     * <p>Implements FR5 of add-plugin-architecture.
     *
     * @return the non-blank discriminator this provider serves; never null
     */
    String provider();

    /**
     * Returns a live, ready-to-poll {@link ExternalCheckClient} for this provider's configured
     * connection; never null. A credential that does not resolve fails closed here, naming the
     * secret — no stage ever runs against an unauthenticated client.
     *
     * <p>This is the factory's single {@code create}: every host-provided collaborator — the secrets
     * seam, the operator subsection, the run's allowlisted variables (NFR-S2, design D5), the time
     * equipment — arrives through {@code context}, because {@code ServiceLoader} instantiates this
     * factory through its public no-arg constructor, before any collaborator exists (FR2, design D2;
     * FR23, design D21 of supervise-daemon-loops-and-embed-dashboard). A provider whose target is
     * fully determined by its connection and check id ignores the run context; every instant a
     * provider stamps reads {@link CheckClientContext#timeEquipment()}, never a clock of its own.
     *
     * @param context everything the host hands this provider; never null
     * @return a live, ready-to-poll client; never null
     */
    ExternalCheckClient create(CheckClientContext context);

    /**
     * The load-time validator for an {@code external} check's provider-owned {@code params}, so a
     * malformed selector is a located {@code ConfigError} aggregated with the loader's own errors
     * rather than a mid-stage adapter failure (FR6).
     *
     * <p>The default returns empty — a provider that defines no per-check params grades none.
     *
     * @return this provider's params validator, or empty when it grades no params
     */
    default Optional<CheckParamsValidator> paramsValidator() {
        return Optional.empty();
    }

    /**
     * The load-time validator for this provider's own {@code factory.check.<provider>} operator
     * subsection (FR4, design D12), exposed through the factory rather than discovered as a second
     * SPI: a separate registry could drift from the factory registry, whereas one obtained from
     * each discovered factory is keyed identically to it by construction (design D1, D3).
     *
     * <p>The default returns empty — a provider whose subsection is opaque grades no content.
     *
     * @return this provider's subsection validator, or empty when it grades no subsection content
     */
    default Optional<CheckSubsectionValidator> subsectionValidator() {
        return Optional.empty();
    }

    /**
     * Declares this provider's credential environment variable names, resolved from its configured
     * connection (FR17, design D11, NFR-S1): the composition root unions the declarations of every
     * selected provider and derives the child-environment scrub and never-allowlist set from that
     * union alone, so no core source names a vendor credential constant.
     *
     * <p>Takes the resolved {@code subsection} because a credential name can be configuration data
     * — a named connection profile supplies it rather than a compile-time constant — which a no-arg
     * declaration could not see.
     *
     * <p>The default returns an empty list — a provider that reads no credential from the
     * environment needs no override; declaring this is mandatory for
     * any provider that does.
     *
     * @param subsection this provider's {@code factory.check.<provider>} operator subsection;
     *     never null
     * @return the credential environment variable names this provider declares; never null
     */
    default List<String> credentialEnvVars(Map<String, Object> subsection) {
        return List.of();
    }

    /**
     * Declares the credential environment variable names <em>one check's</em> {@code params} resolve
     * to (FR11, FR17, design D11), for the provider whose credentials are named per check rather
     * than per connection — the built-in {@code http} provider, which serves arbitrary endpoints and
     * therefore has no single connection subsection to draw one name from.
     *
     * <p>The composition root unions this over every {@code external} check the loaded pipeline
     * declares, alongside {@link #credentialEnvVars(Map)}, so a manifest-named credential is
     * scrubbed from every child environment and barred from the passthrough allowlist exactly like a
     * connection-declared one. A manifest naming an operator passthrough variable as its credential
     * therefore fails the run visibly rather than leaking it (NFR-S1).
     *
     * <p>The default returns an empty list: a provider drawing its credential from its connection
     * subsection declares it there and nothing per check.
     *
     * @param params one {@code external} check's provider-owned params; never null
     * @return the credential environment variable names this check resolves; never null
     */
    default List<String> checkCredentialEnvVars(Map<String, Object> params) {
        return List.of();
    }

    /**
     * This provider's pin-path contribution hook (FR15, design D3): the paths whose content defines
     * a check for this provider — a platform provider names its own definition file, a provider
     * with no repo-borne definition contributes none. The pin-check guard unions the contribution
     * with the stage law's declared pin paths before the first poll, identically for a discovered
     * plugin and a bundled provider.
     *
     * <p>The default contributes nothing, so a pinless check passes the guard vacuously.
     *
     * @return this provider's pin contribution; never null
     */
    default ExternalCheckPinContributor pinContributor() {
        return ExternalCheckPinContributor.none();
    }
}
