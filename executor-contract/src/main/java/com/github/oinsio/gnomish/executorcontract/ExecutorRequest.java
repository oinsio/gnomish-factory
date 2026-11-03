package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One executor run, requested in exactly one role; the document the executor reads as its standard
 * input. It carries no credential value. Role selects the inputs, not the shape: every role shares
 * the workspace, policy, settings, bounds, feedback and continuation, and only {@link RoleInputs}
 * differs. Wire shape ({@code executor-request.schema.json}): {@code {"role", "workspace",
 * "policy", "roleInputs", "settings", "bounds", "feedback", "continuation"?}}.
 *
 * <p>The settings node is the law's value, shared and not copied: no holder mutates it.
 *
 * <p>Implements FR1, FR11 of define-executor-contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutorRequest(
        Role role,
        Workspace workspace,
        Policy policy,
        RoleInputs roleInputs,
        ObjectNode settings,
        Bounds bounds,
        List<Finding> feedback,
        @Nullable Continuation continuation) {

    /** Refuses an unknown role and role inputs that lack what the role requires. */
    public ExecutorRequest {
        Objects.requireNonNull(role, "role is unknown or absent");
        if (!role.accepts(roleInputs)) {
            throw new IllegalArgumentException("roleInputs lack what role " + role.wire() + " requires");
        }
        feedback = List.copyOf(feedback);
    }
}
