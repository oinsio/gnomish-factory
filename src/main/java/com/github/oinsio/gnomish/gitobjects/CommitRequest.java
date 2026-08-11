package com.github.oinsio.gnomish.gitobjects;

import java.util.List;
import java.util.Optional;

/**
 * A single atomic request to build a commit and advance a ref to it. {@code parent}'s tree is the
 * starting point the {@code edits} are applied over; {@code expectedTip} is the compare-and-swap
 * precondition git enforces on {@code update-ref}: present means "the ref must currently point
 * here", empty means "the ref must not yet exist" (branch creation). A concurrently moved tip fails
 * the write rather than being force-overwritten (design D19, FR25).
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
public record CommitRequest(
        String ref, Optional<ObjectId> expectedTip, ObjectId parent, List<TreeEdit> edits, CommitMetadata metadata) {

    @SuppressWarnings({"ConstantValue", "ConstantConditions", "OptionalAssignedToNull"}) // defensive: guards
    // construction paths NullAway cannot see (e.g. Groovy specs), where a null argument would
    // otherwise reach here unchecked
    public CommitRequest {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("commit ref must not be blank");
        }
        if (expectedTip == null) {
            throw new IllegalArgumentException("expectedTip must be an Optional, not null");
        }
        if (parent == null) {
            throw new IllegalArgumentException("commit parent must not be null");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("commit metadata must not be null");
        }
        edits = List.copyOf(edits);
    }
}
