package com.github.oinsio.gnomish.app.doctor;

import org.jspecify.annotations.NullMarked;

/**
 * Every doctor check, in report order, each with its stable id — the key the {@code --json}
 * document carries — and the name its line shows (FR1, FR5, FR8 of add-doctor-command; design D3).
 * The fourteen checks of FR1 come first; {@link #HOOKS_PATH} is FR8's, which no startup path
 * decides; {@link #TRACKER_PROBE} is FR3's, the one {@link #online()} check, which {@code --online}
 * adds. One constant names one {@link DoctorCheck}; the command reports in this order.
 *
 * <p>Implements FR1, FR3, FR5, FR8 of add-doctor-command.
 */
@NullMarked
public enum DoctorCheckId {
    FACTORY_VERSION("factory-version", "factory version"),
    GIT_FLOOR("git-floor", "git version floor"),
    CONFIGURATION("configuration", "operator configuration"),
    REGISTRATION("project-registration", "project registration"),
    REF_FORMAT("ref-format", "clone ref backend"),
    PIPELINE("pipeline", "pipeline law"),
    SECRETS("secrets", "declared secret"),
    AGENT_CREDENTIAL("agent-credential", "agent credential"),
    DOCKER("docker", "Docker runtime"),
    SANDBOX_IMAGE("sandbox-image", "sandbox image"),
    GUARD_IMAGE("guard-image", "guard image"),
    AGENT_CLI("agent-cli", "agent CLI binary"),
    LAUNCHER_HELPER("launcher-helper", "process-group launcher"),
    LEGACY_LAYOUT("legacy-layout", "pre-registry layout"),
    HOOKS_PATH("hooks-path", "clone hooks path"),
    TRACKER_PROBE("tracker-probe", "tracker read probe", true);

    private final String id;
    private final String displayName;
    private final boolean online;

    DoctorCheckId(String id, String displayName) {
        this(id, displayName, false);
    }

    DoctorCheckId(String id, String displayName, boolean online) {
        this.id = id;
        this.displayName = displayName;
        this.online = online;
    }

    /** The stable id the JSON document carries. */
    public String id() {
        return id;
    }

    /** The name the check's line shows. */
    public String displayName() {
        return displayName;
    }

    /**
     * Whether the check reaches the network, and so runs only under {@code --online} (FR3 of
     * add-doctor-command): without it the check is not run and has no line.
     */
    public boolean online() {
        return online;
    }
}
