package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.Optional;

/**
 * The narrow face of {@link TrackerWiring} the per-ref dispatch depends on (design D2 of
 * collapse-composition-roots, the {@code TrackerWiring} row): turning one {@code <ref>} token into
 * a {@link TaskRef}, and refusing a ref that names a repository the configured adapter cannot
 * reconcile. {@link TakeDispatcher} takes this interface rather than the whole wiring, so its
 * signature names exactly what it uses — and so it holds nothing through which the credential
 * seam behind the wiring could be reached (NFR-S1: use of the secrets is granted, possession is
 * not).
 *
 * <p>Implements FR4, NFR-S1 of collapse-composition-roots; FR9 of add-tracker-port.
 */
interface RefResolution {

    /**
     * Builds the {@link TaskRef} for one explicit-mode {@code <ref>} string (FR9 of
     * add-tracker-port): a ref matching the short-ref shape ({@code 42}, {@code #42}) is expanded
     * via the adapter registered for {@code trackerConfig.type()}; anything else (an
     * already-canonical ref) is wrapped as-is, with no registry lookup at all.
     *
     * @throws UsageException if {@code rawRef} looks like a short ref but no adapter is registered
     *     for {@code trackerConfig.type()}
     */
    TaskRef resolveRef(String rawRef, TrackerConfig trackerConfig);

    /**
     * Asks {@code factory} whether {@code ref} names a repository it cannot reconcile to the
     * configured binding (FR9, design D8 of add-tracker-port); the credentials the check may need
     * are supplied by the wiring, never by the caller.
     *
     * @return the refusal sentence, or empty when the ref is acceptable
     */
    Optional<String> refuseForeignRef(TrackerAdapterFactory factory, TrackerConfig trackerConfig, TaskRef ref);
}
