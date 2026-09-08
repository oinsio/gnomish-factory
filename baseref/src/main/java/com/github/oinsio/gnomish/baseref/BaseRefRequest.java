package com.github.oinsio.gnomish.baseref;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Everything the base decision rests on, as values.
 *
 * <p>A parameter object rather than an argument list, for the reason the project's parameter rule
 * names: three of these are ref-shaped strings, and three adjacent strings are a transposition
 * hazard no compiler catches. They are separated here by the two non-string components so a
 * mis-ordered construction does not compile, and the request is built once at the funnel.
 *
 * <p>Implements FR4, FR5, FR8, FR10 of add-base-ref-resolution.
 *
 * @param explicitBase the ref an operator passed as {@code --base}, or null. Wins outright and is
 *     not held against the allowed bases
 * @param designator the base the task named, already classified into one of the three shapes
 * @param allowedBases the project's allowed bases, from the trusted tier bound at startup
 * @param configuredDefault the project's {@code task-branch.base.default}, or null when it declared
 *     none. The loader has already checked it against the allowed bases, so resolution does not
 *     re-check it
 * @param mode whether a human is at the clone — the one thing that decides if the local HEAD is
 *     reachable at all
 * @param defaultBranch the repository default branch as the remote reported it, or null when there
 *     is no remote or the caller could not ask
 */
public record BaseRefRequest(
        @Nullable String explicitBase,
        BaseDesignator designator,
        AllowedBases allowedBases,
        @Nullable String configuredDefault,
        ResolutionMode mode,
        @Nullable String defaultBranch) {

    /** The three non-null components are the policy itself; absent inputs are the nullable ones. */
    public BaseRefRequest {
        Objects.requireNonNull(designator, "designator");
        Objects.requireNonNull(allowedBases, "allowedBases");
        Objects.requireNonNull(mode, "mode");
    }
}
