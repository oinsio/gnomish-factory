package com.github.oinsio.gnomish.app.conformance;

import com.github.oinsio.gnomish.executorcontract.Role;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The thirteen cases of the conformance kit (FR12, M4 of define-executor-contract; design D18, D19;
 * the re-poll case FR12 of migrate-verify-to-executor-contract, design D11), in the order the kit runs
 * and reports them. Each case's name is the word its report line and its
 * scratch working copy carry: the misbehaving fake breaks a case by the request's workspace path
 * ending in {@code /<name>}.
 *
 * <p>Kept in sync with {@code test-fixtures/src/main/resources/stand-in/steps/executor-run.sh}: the
 * case names, which the script's header lists and its {@code BROKEN} switch accepts, are exactly
 * these, the kit owning them ({@code KitCaseScriptIdentitySpec} reads both). No shared classpath: the
 * script is a POSIX stand-in of the test library.
 *
 * <p>Implements FR12, M4 of define-executor-contract; FR11, FR12 of migrate-verify-to-executor-contract.
 */
@NullMarked
public enum KitCase {
    /** The describe answer validates against the describe schema and the preflight accepts it. */
    DESCRIBE("describe", null),
    /** The settings schema the describe declares is one the factory can use, and accepts the settings. */
    SETTINGS_SCHEMA("settings-schema", null),
    /** A {@code transform} start completes, with documents the schemas accept. */
    TRANSFORM("transform", Role.TRANSFORM),
    /** A {@code verdict} start completes with a verdict. */
    VERDICT("verdict", Role.VERDICT),
    /** A {@code decision} start completes choosing one of the offered options, or asks with two or more. */
    DECISION("decision", Role.DECISION),
    /** A start in a role the describe does not list writes no {@code completed} result. */
    UNKNOWN_ROLE("unknown-role", null),
    /** A start's documents fit the factory's caps. */
    OVERSIZED_OUTPUT("oversized-output", null),
    /** A start that writes on stderr still delivers its result in the result file. */
    STDERR_NOISE("stderr-noise", null),
    /** A start that writes on stdout still delivers its result in the result file. */
    NON_JSON_STDOUT("non-json-stdout", null),
    /** A start under the pending settings returns {@code pending}, and its poll ends the run. */
    PENDING_POLL("pending-poll", null),
    /**
     * A start under the pending settings returns {@code pending}, its key is polled to a terminal
     * status, and a re-poll of the key that ended the run answers that run again, alike.
     */
    PENDING_REPOLL("pending-repoll", null),
    /** A request carrying a field the contract does not define is answered as without it. */
    UNKNOWN_REQUEST_FIELDS("unknown-request-fields", null),
    /** A {@code decision} start's answer is well-formed. */
    MALFORMED_ANSWER("malformed-answer", Role.DECISION);

    private final String word;
    private final @Nullable Role role;

    KitCase(String word, @Nullable Role role) {
        this.word = word;
        this.role = role;
    }

    /**
     * The case's name: its report line's, and the last segment of its working copy's path.
     *
     * @return the name; never blank
     */
    public String word() {
        return word;
    }

    /**
     * The role the case is bound to: a role case's own, and {@code decision} for the malformed answer.
     *
     * @return the role, or {@code null} when the case runs in the candidate's first live role
     */
    public @Nullable Role role() {
        return role;
    }

    /**
     * Whether the case starts under the pending settings and polls the run it starts, and so applies
     * only to a candidate whose describe declares {@code poll}.
     *
     * @return {@code true} for the pending case and the re-poll case
     */
    public boolean pending() {
        return this == PENDING_POLL || this == PENDING_REPOLL;
    }

    /**
     * Whether the case exists only on the wire — a document or a process to break — and so is not
     * applicable to an executor driven in process (design D19).
     *
     * @return {@code true} for unknown request fields, oversized output, stderr noise and non-JSON stdout
     */
    public boolean wireOnly() {
        return this == UNKNOWN_REQUEST_FIELDS
                || this == OVERSIZED_OUTPUT
                || this == STDERR_NOISE
                || this == NON_JSON_STDOUT;
    }
}
