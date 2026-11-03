package com.github.oinsio.gnomish.executorcontract;

/**
 * The working copy a run acts on, as the execution environment names it. Wire shape: {@code
 * {"path": string}}.
 *
 * <p>Implements FR1 of define-executor-contract.
 */
public record Workspace(String path) {

    /** Refuses a blank path. */
    public Workspace {
        WireChecks.nonBlank(path, "workspace.path");
    }
}
