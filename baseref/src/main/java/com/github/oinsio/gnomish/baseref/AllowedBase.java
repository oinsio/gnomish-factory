package com.github.oinsio.gnomish.baseref;

import java.util.Objects;

/**
 * One entry of the allowed bases: the pattern that names a set of refs, and what those refs are for.
 *
 * <p>Implements FR1 of add-base-ref-resolution.
 *
 * @param pattern the ref-name pattern; never null
 * @param role what the matched refs are for; never null — {@link BranchRole#defaultRole()} when the
 *     project declared none
 */
public record AllowedBase(BasePattern pattern, BranchRole role) {

    /** Rejects a half-built entry: both components are load-bearing for matching and reporting. */
    public AllowedBase {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(role, "role");
    }

    /**
     * An allowed base that declares no role, which is the common case: a project listing the branches
     * work starts from without classifying them.
     *
     * @param pattern the compiled pattern
     * @return the entry with {@link BranchRole#defaultRole()}
     */
    public static AllowedBase of(BasePattern pattern) {
        return new AllowedBase(pattern, BranchRole.defaultRole());
    }
}
