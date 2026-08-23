package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.app.git.ProjectScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists one sweep pass's objects across every identity the pass owns (design D4 of
 * normalize-project-identity-url). Docker's {@code --filter} conjoins its predicates and offers no
 * OR across two values of one label key, so a scope carrying a legacy identity is read with one
 * extra listing per object kind and the results merged by object name — the command builders in
 * {@link DockerLifecycleCommands} stay untouched, and the extra cost stays visible and bounded
 * (NFR-C1): nothing extra is issued when no legacy identity exists.
 *
 * <p>Fail-closed applies per listing, not per scope (design D5): a failed legacy listing throws
 * {@link DockerUnavailableException} out of here exactly as the stamped-identity listing does, and
 * aborts the whole pass. Treating it as best-effort would make "the legacy listing broke"
 * indistinguishable from "there are no legacy objects", which is the false-empty the fail-closed
 * rule exists to forbid — and it would do so for the objects most at risk of being orphaned.
 *
 * <p>One instance per pass: it tallies the legacy-labelled objects seen across all three kinds so
 * the transition can be reported once (NFR-O1) rather than per listing.
 *
 * <p>Implements FR3, NFR-R2, NFR-O1, NFR-C1 of normalize-project-identity-url.
 */
final class ScopedObjectListing {

    private static final Logger log = LoggerFactory.getLogger(ScopedObjectListing.class);

    private final SandboxLifecycleObjectReader reader;
    private final ProjectScope scope;

    private int legacyFound;

    ScopedObjectListing(SandboxLifecycleObjectReader reader, ProjectScope scope) {
        this.reader = reader;
        this.scope = scope;
    }

    /**
     * Every object of one kind this factory owns, under any of its identities.
     *
     * @param kind the Docker object type being listed; never null
     * @param listArgv builds the listing argv for one project identity; never null
     * @return the merged objects, this factory's stamped identity first; never null
     * @throws DockerUnavailableException if any of the scope's listings could not be obtained
     */
    List<ListedDockerObject> list(ObjectKind kind, Function<String, List<String>> listArgv) {
        List<ListedDockerObject> stamped = reader.list(kind, listArgv.apply(scope.identity()));
        Optional<String> legacy = scope.legacyIdentity();
        if (legacy.isEmpty()) {
            return stamped;
        }
        List<ListedDockerObject> legacyObjects = reader.list(kind, listArgv.apply(legacy.get()));
        legacyFound += legacyObjects.size();
        Map<String, ListedDockerObject> merged = new LinkedHashMap<>();
        stamped.forEach(object -> merged.put(object.name(), object));
        legacyObjects.forEach(object -> merged.putIfAbsent(object.name(), object));
        return List.copyOf(merged.values());
    }

    /**
     * Records the transition in the log once per pass, and only when it actually happened (NFR-O1)
     * — an operator upgrading across normalization sees why the pass touched objects a
     * differently-labelled factory created, and sees the count drain to zero as those objects are
     * reclaimed. Nothing is logged when the scope carries no legacy identity or found no object
     * under it, so a settled installation stays quiet.
     */
    void reportLegacyObjects() {
        if (legacyFound > 0) {
            log.info(
                    "sandbox lifecycle sweep included {} object(s) still labelled with the legacy project identity {}",
                    legacyFound,
                    scope.legacyIdentity().orElseThrow());
        }
    }
}
