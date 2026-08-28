package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.List;
import java.util.Optional;

/**
 * Constructs a live {@link Tracker} for one registered adapter {@code type} (tasks 5.13, 5.15),
 * and expands a short ref (`42`, `#42`) into that type's canonical {@link TaskRef} (task 5.14),
 * mirroring the adapter-layer {@code TrackerSeamValidator}'s own registry
 * philosophy for config validation: the core CLI knows only this minimal seam, never a concrete
 * adapter class. The {@code TakeCommand} use case resolves an implementation from a {@code Map<String,
 * TrackerAdapterFactory>} keyed by {@link TrackerConfig#type()}, at run time, once per invocation
 * — never as a Spring {@code @Bean} bound to one concrete adapter (which adapter is active depends
 * on the project's own {@code .gnomish/config.yaml}, read per invocation like {@code
 * PipelineDefinition} itself).
 *
 * <p>{@code create} takes the minted {@link com.github.oinsio.gnomish.app.port.tracker.InstanceId}
 * string alongside {@code config} (task 5.15): several GitHub collaborators (e.g. {@code
 * GithubStateWrites}, {@code GithubCorrespondence}, {@code GithubDecisions}) stamp the instance id
 * into structural markers at construction time, so {@code TakeCommand} mints the {@code
 * InstanceId} before resolving the tracker rather than after.
 *
 * <p>Implementations are discovered through {@code ServiceLoader} and keyed by {@link #type()}, so
 * they must offer a public no-arg constructor and take their collaborators as method arguments
 * (FR1, FR2 of add-plugin-architecture).
 *
 * <p>Implements FR9, FR17 of add-tracker-port; FR1, FR2, FR4, FR17 of add-plugin-architecture.
 */
public interface TrackerAdapterFactory {

    /**
     * This adapter's {@code tracker.type} discriminator — the key its factory is registered under in
     * the {@code ServiceLoader}-built registry, and the value an operator writes as {@code
     * tracker.type} in {@code .gnomish/config.yaml} (e.g. {@code "github"}, {@code "inmemory"}).
     *
     * <p>Two discovered factories claiming the same discriminator, or a factory returning a blank
     * one, fail the registry build with a named error at startup rather than at first use (NFR-R1).
     *
     * <p>Implements FR1 of add-plugin-architecture.
     *
     * @return the non-blank discriminator this adapter serves; never null
     */
    String type();

    /**
     * Returns a live, ready-to-use {@link Tracker} for {@code config.type()}; never null.
     *
     * <p>{@code secrets} arrives as a method argument rather than through the constructor because
     * {@code ServiceLoader} instantiates this factory through its public no-arg constructor, before
     * any collaborator exists (FR2, design D2 of add-plugin-architecture).
     *
     * @param secrets the seam through which this adapter resolves its named credentials (NFR-S1);
     *     never null
     * @param config the project's validated {@code tracker} section; never null
     * @param instanceId this process's minted {@link
     *     com.github.oinsio.gnomish.app.port.tracker.InstanceId} value, stamped into structural
     *     markers by adapters that need it at construction time; never null
     */
    Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId);

    /**
     * Returns a live {@link Tracker} that can stamp the claim epoch of the tenure it is writing
     * under into its own tracker writes (FR13 of harden-task-branch-contract).
     *
     * <p>This is the form the composition root calls. The default ignores {@code epochs} and falls
     * back to {@link #create(SecretsProvider, TrackerConfig, String)}, because epoch stamping is
     * adapter-optional: a tracker whose writes are already atomic (the in-memory reference) has no
     * frozen intermediate state to attribute, and a tracker with its own monotonic source may
     * choose to carry the epoch differently. An adapter whose writes are physically non-atomic —
     * the GitHub adapter — overrides this method and stamps every marker it writes, so a reader can
     * classify an artifact of a superseded tenure as stale rather than as current truth.
     *
     * <p>Implementations override <em>this</em> method, never both: the three-argument form stays
     * the caller-facing entry point and always routes here.
     *
     * @param epochs this instance's tenure record — which epoch it holds on a given task right now;
     *     {@link ClaimEpochSource#NONE} for a caller that never claims; never null
     */
    default Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId, ClaimEpochSource epochs) {
        return create(secrets, config, instanceId);
    }

    /**
     * Expands a recognized short ref (a bare or {@code #}-prefixed non-negative integer, e.g.
     * {@code 42}/{@code #42}) into this adapter type's canonical {@link TaskRef}, using {@code
     * config.subsection()}'s adapter-owned keys (e.g. GitHub's {@code api-url}/{@code repo}) to
     * mint the full id (FR9).
     *
     * @param config the project's validated {@code tracker} section, carrying the adapter
     *     subsection needed to build a canonical id; never null
     * @param rawRef the raw short ref, already recognized by the caller as short (e.g. {@code
     *     "42"} or {@code "#42"}); never null
     * @return the expanded, canonical {@link TaskRef}
     */
    TaskRef expandRef(TrackerConfig config, String rawRef);

    /**
     * Checks a fully-resolved canonical {@code ref} against this adapter's configured binding
     * (FR9, design D8): returns a refusal message when the ref names a target this adapter refuses
     * to act on — for GitHub, an {@code owner/repo} that is neither the configured repo nor a
     * rename predecessor of it — or {@link Optional#empty()} to proceed. The {@code TakeCommand}
     * use case calls this in explicit mode after ref resolution
     * and before {@code fetchTask}, so a foreign id is refused (exit 15) instead of being silently
     * acted on in the foreign repo.
     *
     * <p>The default returns empty — an adapter whose canonical ids cannot name a foreign target
     * (e.g. the in-memory reference adapter, whose refs are opaque and carry no repo binding)
     * needs no override.
     *
     * @param secrets the seam through which this adapter resolves its named credentials (FR2,
     *     design D2 of add-plugin-architecture); never null
     * @param config the project's validated {@code tracker} section; never null
     * @param ref the fully-resolved canonical {@link TaskRef} explicit mode is about to act on;
     *     never null
     * @return a refusal message naming the mismatch, or empty to proceed
     */
    default Optional<String> refuseForeignRef(SecretsProvider secrets, TrackerConfig config, TaskRef ref) {
        return Optional.empty();
    }

    /**
     * The load-time validator for this adapter's own {@code tracker.<type>} config subsection, so a
     * malformed subsection is a located {@code ConfigError} aggregated with the core's own load
     * errors rather than a mid-{@code take} adapter failure (FR4, FR17).
     *
     * <p>Exposed through the factory rather than discovered as a second SPI: a separate registry
     * could drift from the factory registry, whereas one obtained from each discovered factory is
     * keyed identically to it by construction (design D1, D3 of add-plugin-architecture).
     *
     * <p>The default returns empty — an adapter whose subsection is opaque (e.g. the in-memory
     * reference adapter, which has no subsection at all) needs no override.
     *
     * @return this adapter's subsection validator, or empty when it grades no subsection content
     */
    default Optional<TrackerSubsectionValidator> subsectionValidator() {
        return Optional.empty();
    }

    /**
     * Declares this adapter's credential environment variable names (design D17, NFR-S1): the
     * agent process launcher removes every declared name from the CLI subprocess's environment
     * regardless of {@code factory.agent-cli-env-passthrough}, so tracker credentials never reach
     * the gnome. The default returns an empty list — an adapter with no credentials (e.g. the
     * in-memory reference adapter) needs no
     * override; declaring this is mandatory for any adapter that DOES read a credential from the
     * environment (e.g. GitHub's {@code GNOMISH_GITHUB_TOKEN}).
     *
     * <p>Takes the resolved {@code config} because a credential name can be configuration data — a
     * named connection profile supplies it rather than a compile-time constant — so a no-arg
     * declaration could not see it, and core must not name any vendor's constant itself (FR17,
     * design D11 of add-plugin-architecture).
     *
     * @param config the project's validated {@code tracker} section, carrying whatever connection
     *     data names this adapter's credentials; never null
     * @return the credential environment variable names this adapter declares; never null, empty
     *     when this adapter has no credentials
     */
    default List<String> credentialEnvVars(TrackerConfig config) {
        return List.of();
    }
}
