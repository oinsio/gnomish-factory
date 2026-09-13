package com.github.oinsio.gnomish.app.take;

/**
 * The closing paragraph every deterministic base park shares: what the factory did <em>not</em>
 * spend, why re-claiming would not help, and the one remedy that would. The four park reports of
 * add-base-ref-resolution ({@link FreshClaimBaseReport#underdetermined}, {@link
 * FreshClaimBaseReport#refused}, {@link ResumeBaseReport#unresolved}, {@link BaseLawReport#of})
 * repeated it verbatim; extracted at the fourth occurrence so the sentence an operator learns to
 * recognize cannot drift between reports (rule of three, {@code .claude/rules/manual-sync-pairs.md}).
 *
 * <p>Only the remedy differs per report, so it is the one thing a caller supplies. Pure text, no
 * I/O, nothing untrusted: every argument is a factory-authored literal, so no {@code LogText} pass
 * belongs here — the untrusted fields are sanitized by each report at its own call site.
 *
 * <p>Implements FR2, FR6, FR9, FR12, FR13 of add-base-ref-resolution.
 */
final class ParkedBaseTrailer {

    /** The remedy of both reports about a ref that will not resolve or refresh. */
    static final String REPOINT_BASE = "Fix or re-point the base and return the task to work.";

    private ParkedBaseTrailer() {}

    /**
     * Renders the trailer.
     *
     * @param remedy the report's own closing instruction, a factory-authored sentence; never blank
     * @return the trailer paragraph; never blank
     */
    static String withRemedy(String remedy) {
        return "No stage attempt was spent and the claim was not released: the failure is deterministic,"
                + " so re-claiming would only repeat it. " + remedy;
    }
}
