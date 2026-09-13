package com.github.oinsio.gnomish.baseref;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The project's declared base policy, as the two values resolution consults: the
 * allowed bases and the ref to take when nobody named one.
 *
 * <p>The pair travels together because it is read together and written together — one {@code task-branch.base}
 * section of one {@code config.yaml}, bound once at startup as trusted-tier configuration. Handing
 * the funnel two loose arguments instead would let a caller pass one project's allowed bases with another's
 * default, which no compiler would catch.
 *
 * <p>The section is <em>parsed</em> elsewhere: this module knows nothing of YAML, of {@code
 * config.yaml}, or of which ref the file was read from. What arrives here is already validated —
 * the patterns compiled, the roles known, the default checked against them — which is why
 * {@link BaseRefResolver} re-checks none of it.
 *
 * <p>Implements FR1, FR2 of add-base-ref-resolution.
 *
 * @param allowedBases the allowed bases; {@link AllowedBases#empty()} for a project that declared none
 * @param defaultRef the project's {@code task-branch.base.default}, or null when it declared none. Already
 *     known to be allowed when the project declared one
 */
public record BaseDefinition(
        AllowedBases allowedBases, @Nullable String defaultRef) {

    /** The definition of a project with no {@code task-branch.base} section at all. */
    private static final BaseDefinition NONE = new BaseDefinition(AllowedBases.empty(), null);

    /** The allowed bases are the policy; a definition without them could not be consulted. */
    public BaseDefinition {
        Objects.requireNonNull(allowedBases, "allowedBases");
    }

    /**
     * The zero-configuration definition: no allowed base, no default. What a project without a
     * {@code task-branch.base} section declares, and what a caller that could not read one may safely resolve under —
     * resolution then falls through to the repository default branch.
     *
     * @return the empty definition
     */
    public static BaseDefinition none() {
        return NONE;
    }
}
