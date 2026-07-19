package com.github.oinsio.gnomish.adapter.git.state;

import java.io.Serial;

/**
 * Thrown when a {@code .gnomish-task/} state file (e.g. {@code task.json},
 * {@code state.json}) carries a {@code "version"} this build does not support,
 * or is missing the field entirely. The message names both the offending file
 * and the found/supported versions (FR4), e.g. {@code "task.json: unsupported
 * version 2 (supported: 1)"} — the refusal a future resume or {@code
 * status}/{@code usage} reader surfaces to the operator.
 *
 * <p>Unchecked: a state file that fails this gate is not a recoverable
 * condition inside the parse — callers let it propagate to their own CLI
 * boundary rather than catching it mid-flow (same idiom as {@link
 * com.github.oinsio.gnomish.app.UsageException}/{@link
 * com.github.oinsio.gnomish.app.PipelineLoadFailedException}).
 *
 * <p>Implements FR4 of add-git-workflow.
 */
public final class UnsupportedStateFileVersionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String fileName;
    private final int foundVersion;
    private final int supportedVersion;

    /**
     * @param fileName the state file's name, e.g. {@code "task.json"}; never blank
     * @param foundVersion the version found on the wire, or {@code -1} if the
     *     {@code "version"} field was missing entirely
     * @param supportedVersion the only version this build accepts
     */
    public UnsupportedStateFileVersionException(String fileName, int foundVersion, int supportedVersion) {
        super(renderMessage(fileName, foundVersion, supportedVersion));
        this.fileName = fileName;
        this.foundVersion = foundVersion;
        this.supportedVersion = supportedVersion;
    }

    private static String renderMessage(String fileName, int foundVersion, int supportedVersion) {
        String found = foundVersion < 0 ? "missing" : "unsupported version " + foundVersion;
        return fileName + ": " + found + " (supported: " + supportedVersion + ")";
    }

    /**
     * Returns the state file's name, e.g. {@code "task.json"}.
     *
     * @return the state file's name
     */
    public String fileName() {
        return fileName;
    }

    /**
     * Returns the version found on the wire, or {@code -1} if {@code "version"}
     * was missing entirely.
     *
     * @return the found version, or {@code -1} if missing
     */
    public int foundVersion() {
        return foundVersion;
    }

    /**
     * Returns the only version this build accepts.
     *
     * @return the supported version
     */
    public int supportedVersion() {
        return supportedVersion;
    }
}
