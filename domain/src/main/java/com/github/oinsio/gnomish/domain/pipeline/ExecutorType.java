package com.github.oinsio.gnomish.domain.pipeline;

/**
 * The executor kind of a stage's Mechanism section (stage contract §5, design
 * D5a): {@link #API} drives the ai-provider API directly; {@link #AGENT_CLI}
 * runs an agent CLI as a subprocess in a workspace. The domain enum is pure —
 * the YAML wire values ({@code api} / {@code agent-cli}) are mapped by the
 * adapter DTOs (task 5.1), and an unknown wire value is a located structural
 * error there (FR5), never a domain concern.
 *
 * <p>Implements FR2 of load-pipeline-config.
 */
public enum ExecutorType {
    /** Direct ai-provider API execution. */
    API,
    /** Agent CLI subprocess execution in a workspace. */
    AGENT_CLI
}
