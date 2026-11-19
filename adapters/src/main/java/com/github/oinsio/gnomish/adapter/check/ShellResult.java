package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.executorcontract.SarifLevel;
import com.github.oinsio.gnomish.executorcontract.SarifResult;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * One result a {@code shell} check reports (FR5 of migrate-verify-to-executor-contract): a SARIF
 * result, and the details the legacy findings file or the synthetic tail result carries beside it.
 * A legacy {@code {"findings":[…]}} entry names no level, so it is an {@code error} result whose
 * location is its URI; a SARIF result is relayed as the file wrote it, with no details.
 *
 * <p>The findings-funnel entry of a {@code shell} check: the executor contract's answer carries it
 * as a SARIF result, its details as the result's own details ({@link #asSarif}, {@code
 * properties.details}, design D19), apart from the message; the engine reads that result through the
 * one contract → domain mapping, whose reading of a SARIF result ({@code SarifResultReading}) makes
 * the details the finding's own field.
 *
 * <p>A class rather than a record: PIT cannot hot-swap a mutated method into a record (the JVMTI
 * redefinition limit of {@code testing.md}), and the renderings below hold decisions the mutation
 * gate must see.
 *
 * <p>Implements FR8 of add-manual-run; FR5 of migrate-verify-to-executor-contract.
 */
final class ShellResult {

    private final SarifResult sarif;
    private final @Nullable UntrustedText details;

    /**
     * @param sarif the result as SARIF; never null
     * @param details what the reporter wrote beside the message, or {@code null} for none
     */
    ShellResult(SarifResult sarif, @Nullable UntrustedText details) {
        this.sarif = sarif;
        this.details = details;
    }

    /** The result as SARIF; never null. */
    SarifResult sarif() {
        return sarif;
    }

    /**
     * A legacy findings entry, normalized to an {@code error} result.
     *
     * @param message the entry's message; never null
     * @param location the entry's location, or {@code null}
     * @param details the entry's details, or {@code null}
     * @return the result; never null
     */
    static ShellResult legacy(
            UntrustedText message, @Nullable UntrustedText location, @Nullable UntrustedText details) {
        return new ShellResult(new SarifResult(null, SarifLevel.ERROR, message, location, null), details);
    }

    /**
     * The synthetic {@code error} result of a red command that wrote no valid result: the exit code
     * in the factory's words, the output tail as its details.
     *
     * @param exitCode the code the command exited with
     * @param outputTail the bounded tail of the command's merged output; never null
     * @return the result; never null
     */
    static ShellResult synthetic(int exitCode, String outputTail) {
        return legacy(
                UntrustedText.factory("command exited with status " + exitCode),
                null,
                UntrustedText.subprocess(outputTail));
    }

    /**
     * This result as the executor contract's SARIF answer carries it, its details beside the message
     * when there are any.
     *
     * @return the result; never null
     */
    SarifResult asSarif() {
        return new SarifResult(
                sarif.ruleId(),
                sarif.level(),
                sarif.message(),
                sarif.uri(),
                sarif.startLine(),
                details == null || details.isBlank() ? null : details);
    }
}
