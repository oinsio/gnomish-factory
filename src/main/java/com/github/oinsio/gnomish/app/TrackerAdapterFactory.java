package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.List;
import java.util.Optional;

/**
 * Constructs a live {@link Tracker} for one registered adapter {@code type} (tasks 5.13, 5.15),
 * and expands a short ref (`42`, `#42`) into that type's canonical {@link TaskRef} (task 5.14),
 * mirroring {@link com.github.oinsio.gnomish.adapter.pipeline.TrackerSeamValidator}'s own registry
 * philosophy for config validation: the core CLI knows only this minimal seam, never a concrete
 * adapter class. {@link TakeCommand} resolves an implementation from a {@code Map<String,
 * TrackerAdapterFactory>} keyed by {@link TrackerConfig#type()}, at run time, once per invocation
 * — never as a Spring {@code @Bean} bound to one concrete adapter (which adapter is active depends
 * on the project's own {@code .gnomish/config.yaml}, read per invocation like {@code
 * PipelineDefinition} itself).
 *
 * <p>{@code create} takes the minted {@link com.github.oinsio.gnomish.app.port.tracker.InstanceId}
 * string alongside {@code config} (task 5.15): several GitHub collaborators (e.g. {@code
 * GithubStateWrites}, {@code GithubCorrespondence}, {@code GithubDecisions}) stamp the instance id
 * into structural markers at construction time, so {@link TakeCommand} mints the {@code
 * InstanceId} before resolving the tracker rather than after.
 *
 * <p>Implements FR9, FR17 of add-tracker-port.
 */
public interface TrackerAdapterFactory {

    /**
     * Returns a live, ready-to-use {@link Tracker} for {@code config.type()}; never null.
     *
     * @param config the project's validated {@code tracker} section; never null
     * @param instanceId this process's minted {@link
     *     com.github.oinsio.gnomish.app.port.tracker.InstanceId} value, stamped into structural
     *     markers by adapters that need it at construction time; never null
     */
    Tracker create(TrackerConfig config, String instanceId);

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
     * rename predecessor of it — or {@link Optional#empty()} to proceed. {@link
     * com.github.oinsio.gnomish.app.TakeCommand} calls this in explicit mode after ref resolution
     * and before {@code fetchTask}, so a foreign id is refused (exit 15) instead of being silently
     * acted on in the foreign repo.
     *
     * <p>The default returns empty — an adapter whose canonical ids cannot name a foreign target
     * (e.g. {@link com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerAdapterFactory},
     * whose refs are opaque and carry no repo binding) needs no override.
     *
     * @param config the project's validated {@code tracker} section; never null
     * @param ref the fully-resolved canonical {@link TaskRef} explicit mode is about to act on;
     *     never null
     * @return a refusal message naming the mismatch, or empty to proceed
     */
    default Optional<String> refuseForeignRef(TrackerConfig config, TaskRef ref) {
        return Optional.empty();
    }

    /**
     * Declares this adapter's credential environment variable names (design D17, NFR-S1): the
     * agent process launcher removes every declared name from the CLI subprocess's environment
     * regardless of {@code factory.agent-cli-env-passthrough}, so tracker credentials never reach
     * the gnome. The default returns an empty list — an adapter with no credentials (e.g. {@link
     * com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerAdapterFactory}) needs no
     * override; declaring this is mandatory for any adapter that DOES read a credential from the
     * environment (e.g. GitHub's {@code GNOMISH_GITHUB_TOKEN}).
     *
     * @return the credential environment variable names this adapter declares; never null, empty
     *     when this adapter has no credentials
     */
    default List<String> credentialEnvVars() {
        return List.of();
    }
}
