package com.github.oinsio.gnomish.adapter.agent;

/**
 * The role a {@code claude -p} round plays, and the CLI permission mode that role launches
 * in. The mode is adapter policy fixed per role, never a manifest or operator setting (FR4):
 * an executor round auto-approves file edits inside its working directory ({@code
 * acceptEdits}), a judge vote denies every tool call outside its pre-approved read-only set
 * without waiting for an answer ({@code dontAsk}). No role maps to the mode that skips all
 * permission checks (NFR-S1). {@link AgentCommandLine} takes the role as a required
 * parameter, so no argv can be assembled without a mode.
 *
 * <p>Implements FR1, FR2, FR4, NFR-S1 of fix-operator-blockers (design D1).
 */
enum AgentRole {
    /** A gnome's executor round: edits inside the working directory are auto-approved (FR1). */
    EXECUTOR("acceptEdits"),

    /** A judge vote: anything outside the read-only set is denied without a prompt (FR2). */
    JUDGE("dontAsk");

    private final String permissionMode;

    AgentRole(String permissionMode) {
        this.permissionMode = permissionMode;
    }

    /**
     * The CLI's {@code --permission-mode} token for this role.
     *
     * @return the mode token; never null
     */
    String permissionMode() {
        return permissionMode;
    }
}
