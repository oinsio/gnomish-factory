package com.github.oinsio.gnomish.sandbox.environment;

/**
 * The ownership stamped atomically on every factory-created Docker object at creation (design
 * D5, FR2 of add-serve-sandbox-lifecycle): the mode distinguishing claim-backed tasks from {@code
 * gnomish run} sessions, and the identity of the project this factory instance is scoped to
 * (FR8). Bundled as one value so every creation call site carries the full ownership or none —
 * there is no partial-ownership construction path. {@link
 * ContainerEnvironmentFactory#forTask} assembles it from the installation's mode and the task's
 * project identity (design D12 of make-checkpoint-gate-durable), so each box carries both halves
 * as one value.
 *
 * @param mode the ownership mode; never null
 * @param projectId the project identity every listing SHALL be scoped to; never blank
 */
public record ObjectOwnership(OwnershipMode mode, String projectId) {

    public ObjectOwnership {
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
    }
}
