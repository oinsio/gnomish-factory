package com.github.oinsio.gnomish.baseref;

import java.util.Objects;
import java.util.Optional;

/**
 * The repository default branch, as the remote named it: a validated short branch name that is
 * never the literal {@link BaseRefResolver#LOCAL_HEAD_REF}.
 *
 * <p>Wrapping the name is what makes the default-branch tier single-owner (FR4, FR10, M2 of
 * add-base-ref-resolution). The tier answers "the repository default branch reported by the
 * remote", and that sentence is only true if the value really came from the remote. While the
 * value travelled as a {@code String} — from the git adapter's default-branch discovery through the startup
 * law to the trusted tier — any caller along the way could hand the resolver a stand-in {@code
 * "HEAD"}, and an autonomous claim would branch from whatever the shared factory clone happened to
 * be checked out at while reporting it as origin's choice: a base nobody chose, under a sentence
 * that says someone did. Nothing was red, because every component was correct in isolation; only a
 * whole-tree text scan of a hand-maintained file list stood in the way, and such a list is exactly
 * as complete as its author's memory. The type is the enforcement instead: a stand-in does not
 * compile, and a name that is not a branch name does not construct.
 *
 * <p>{@code HEAD} is refused rather than merely discouraged, and it is refused here rather than by
 * the resolver, because it is the one well-formed ref name that silently changes the tier's
 * meaning. git draws the same line for the same reason — {@code strbuf_check_branch_ref} refuses
 * {@code HEAD} as a branch name outright, so no remote can legitimately report one, and a remote
 * that does is reporting something this factory must not branch from. Every other lexical rule is
 * {@link RefNameSyntax}'s, unchanged: the name goes on to a refresh fetch as a refspec, and it is
 * subprocess output from a remote a human administers (NFR-S3).
 *
 * <p>The manual tier is untouched: a human at a clone still reaches the local {@code HEAD} through
 * {@link BaseRefResolver#LOCAL_HEAD_REF}, which is the one place in the factory where that literal
 * remains legitimate. This type is about the tier that speaks for the remote.
 *
 * <p>Implements FR4, FR5, FR10, M2, NFR-S3 of add-base-ref-resolution.
 *
 * @param name the short branch name, {@code refs/heads/} already stripped; never blank, never
 *     {@code HEAD}, and always a well-formed ref name
 */
public record DefaultBranch(String name) {

    /**
     * Refuses a name no remote may legitimately report as its default branch.
     *
     * @throws IllegalArgumentException when {@link #violation} names one; the message states the
     *     violated rule, for a discovery outcome to turn into an operator report
     * @throws NullPointerException when the name is missing
     */
    public DefaultBranch {
        String violation = violation(name).orElse(null);
        if (violation != null) {
            throw new IllegalArgumentException("invalid default branch '" + name + "': " + violation);
        }
    }

    /**
     * Why a name cannot be a repository default branch — the question the discovery adapter asks
     * before it reports a name onward, so a remote that names something unusable becomes a located
     * operator report rather than an exception the adapter has to translate.
     *
     * @param candidate the name the remote reported, {@code refs/heads/} already stripped
     * @return the violated rule, phrased for a report, or empty when the constructor will accept it
     */
    public static Optional<String> violation(String candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (BaseRefResolver.LOCAL_HEAD_REF.equals(candidate)) {
            return Optional.of("must not be '" + BaseRefResolver.LOCAL_HEAD_REF
                    + "': git itself refuses it as a branch name, and an autonomous run that accepted it would"
                    + " branch from the factory clone's checkout while reporting the remote's choice");
        }
        return RefNameSyntax.refNameViolation(candidate);
    }
}
