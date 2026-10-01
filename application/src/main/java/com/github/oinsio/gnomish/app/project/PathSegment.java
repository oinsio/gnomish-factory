package com.github.oinsio.gnomish.app.project;

/**
 * The check that a value names exactly one folder below its parent: a name that is blank, is
 * {@code .} or {@code ..}, or carries a separator would place a project path outside the folder
 * {@link FactoryHome} assigned it (FR1).
 *
 * <p>Implements FR1 of add-project-registry.
 */
final class PathSegment {

    private PathSegment() {}

    /**
     * Returns {@code value} when it is one safe path segment.
     *
     * @param value the candidate segment
     * @param what what the value names, for the refusal (e.g. {@code clone name})
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is not one safe path segment
     */
    static String require(String value, String what) {
        if (value.isBlank()
                || value.equals(".")
                || value.equals("..")
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "invalid " + what + " '" + value + "': it must be one folder name, not . or ..");
        }
        return value;
    }
}
