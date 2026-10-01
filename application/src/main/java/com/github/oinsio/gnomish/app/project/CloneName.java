package com.github.oinsio.gnomish.app.project;

/**
 * The name of one clone within a registered project — by default the last segment of the clone's
 * path, unique within the project — keying the clone's own worktree folder
 * {@code projects/<name>/worktrees/<clone>/} (FR2, FR9). Unlike a {@link ProjectName} its case is
 * kept, since it comes from a directory the operator already named; it only has to be one safe
 * path segment.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR2, FR9 of add-project-registry.
 *
 * @param value the name; one path segment, neither {@code .} nor {@code ..}
 */
public record CloneName(String value) {

    public CloneName {
        value = PathSegment.require(value, "clone name");
    }

    @Override
    public String toString() {
        return value;
    }
}
