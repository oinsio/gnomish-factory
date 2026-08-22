package com.github.oinsio.gnomish.sandbox.environment;

/**
 * The ownership stamped atomically on every factory-created Docker object at creation (design
 * D5, FR2 of add-serve-sandbox-lifecycle): the mode distinguishing claim-backed tasks from {@code
 * gnomish run} sessions, and the identity of the project this factory instance is scoped to
 * (FR8). Bundled as one value so every creation call site carries the full ownership or none —
 * there is no partial-ownership construction path.
 *
 * @param mode the ownership mode; never null
 * @param projectId the project identity every listing SHALL be scoped to; never blank
 */
record ObjectOwnership(OwnershipMode mode, String projectId) {

    ObjectOwnership {
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
    }
}
