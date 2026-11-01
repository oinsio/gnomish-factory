package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * The abstract policy a request carries; the executor compiles it to its own mechanism, and it is
 * never a vendor tool name. Wire shape: {@code {"kind": "readOnly" | "writeExactly" |
 * "workspaceWrite", "path"?}}, {@code path} present exactly for {@code writeExactly}. Unknown-token
 * arm: an unknown kind is refused, never widened to {@code workspaceWrite}.
 *
 * <p>Implements FR11, NFR-S4 of define-executor-contract.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(Policy.ReadOnly.class),
    @JsonSubTypes.Type(Policy.WriteExactly.class),
    @JsonSubTypes.Type(Policy.WorkspaceWrite.class)
})
public sealed interface Policy {

    /** The run must leave the working copy unchanged. */
    @JsonTypeName("readOnly")
    record ReadOnly() implements Policy {}

    /** The run may write exactly one path of the working copy. */
    @JsonTypeName("writeExactly")
    record WriteExactly(String path) implements Policy {

        /** Refuses a blank path. */
        public WriteExactly {
            WireChecks.nonBlank(path, "policy.path");
        }
    }

    /** The run may write anywhere in the working copy. */
    @JsonTypeName("workspaceWrite")
    record WorkspaceWrite() implements Policy {}
}
