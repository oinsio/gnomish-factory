package com.github.oinsio.gnomish.app.git;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The set of project identities a sweep pass treats as its own (design D4 of
 * normalize-project-identity-url): the one identity every object this factory creates is stamped
 * with, plus — only while the two differ — the <em>legacy</em> identity objects created before URL
 * normalization still carry.
 *
 * <p>The alias exists because a Docker label is immutable for a live object: relabelling means
 * recreating it, and recreating a live box would destroy the very work the sweep exists to
 * protect. So the transition is read-side only. The write side stays single-valued — no object
 * ever carries two project labels, and no object is ever relabelled — which is why {@link
 * #identity()} and not {@link #identities()} is what a creation takes.
 *
 * <p>Docker's {@code --filter} conjoins its predicates and offers no OR across two values of one
 * label key, so "one listing, two identities" is not expressible; a reader iterates {@link
 * #identities()} instead, at a cost of at most one extra listing per object kind per pass, and
 * only while a legacy identity exists (NFR-C1).
 *
 * @param identity the identity every object created by this factory is stamped with; never blank
 * @param legacyIdentity the digest of the raw, un-normalized {@code origin} URL, present only when
 *     it differs from {@code identity} — absent under an override, with no {@code origin}, and
 *     whenever the URL was already normal
 *     <p>Implements FR3, NFR-C1 of normalize-project-identity-url.
 */
public record ProjectScope(String identity, Optional<String> legacyIdentity) {

    public ProjectScope {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(legacyIdentity, "legacyIdentity");
    }

    /**
     * Every identity this factory owns, stamped first: what a listing iterates.
     *
     * @return one element, or two while a legacy identity exists; never empty
     */
    public List<String> identities() {
        return legacyIdentity.map(legacy -> List.of(identity, legacy)).orElseGet(() -> List.of(identity));
    }
}
