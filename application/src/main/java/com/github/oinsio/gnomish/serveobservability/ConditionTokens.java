package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import java.util.Arrays;
import java.util.Optional;

/**
 * The one spelling of a daemon condition's kind on the wire (design D19 of add-sandbox-base-image,
 * NFR-O2): the {@code condition} field of the ledger's {@code daemonCondition} line and of every
 * entry of the snapshot's {@code conditions} section, written and read through this class only, so
 * the ledger, the snapshot and the dashboard name a condition by one word.
 *
 * <p>Implements NFR-O2, UX4 of add-sandbox-base-image.
 */
public final class ConditionTokens {

    /** The remote's token, the one condition every snapshot and every old ledger knows. */
    public static final String REMOTE = "remote";

    private ConditionTokens() {}

    /**
     * The wire token of {@code kind}.
     *
     * @param kind the condition's kind; never null
     * @return its token; never blank
     */
    public static String token(DaemonConditionKind kind) {
        return switch (kind) {
            case REMOTE -> REMOTE;
            case CONTAINER_RUNTIME -> "containerRuntime";
            case CLONE_REF_FORMAT -> "cloneRefFormat";
            case IMAGE_CONTRACT -> "imageContract";
            case BOX_GIT_FLOOR -> "boxGitFloor";
        };
    }

    /**
     * The kind a wire token names.
     *
     * @param token a token as read from a snapshot or a ledger line; may be anything a newer
     *     factory wrote
     * @return the kind, or empty for a token this version does not know, which a reader keeps as
     *     the token it read
     */
    public static Optional<DaemonConditionKind> kind(String token) {
        return Arrays.stream(DaemonConditionKind.values())
                .filter(kind -> token(kind).equals(token))
                .findFirst();
    }
}
