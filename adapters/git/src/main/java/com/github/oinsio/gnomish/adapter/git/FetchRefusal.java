package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A transfer refused by git's object validation, as the one reader of that refusal's grammar
 * (FR5, design D4 of own-git-transfer-argv). Every transfer the owner builds validates what it
 * receives ({@code fetch.fsckObjects}, {@code transfer.fsckObjects}); when an object fails,
 * {@code index-pack} prints one line per object — {@code error: object <id>: <msg-id>: <detail>}
 * — and then the summary {@code fatal: fsck error in packed object}. Both shapes are a refusal:
 * the per-object line names what was refused, the summary alone (a capped or partial capture)
 * names only that something was.
 *
 * <p>Four sites grade a failed fetch — the harvest's classification, the two base fetches'
 * outcome mapping, the task-branch locate's — and each asks {@link #parse} before its own daemon,
 * non-fast-forward or probe decision, then maps a present value in its own vocabulary: a boundary
 * violation of the box, a task-level park. None of them reads this grammar itself, so git's
 * wording is matched in one place (NFR-R1: a refused object is the repository's or the box's,
 * never the daemon's, so it must never reach the outage accounting).
 *
 * <p>Both fields stay {@link UntrustedText}: they are git's words about an object a box or a
 * remote authored, re-minted in the subprocess family they arrived in, and a report renders them
 * through an exit (NFR-O2).
 *
 * <p>Implements FR5, NFR-R1, NFR-O2 of own-git-transfer-argv.
 *
 * @param messageId the fsck message id git named ({@code missingEmail}, {@code badTree}, ...), or
 *     the summary's own wording when no per-object line was captured
 * @param object the id of the object git refused; blank when the summary alone was captured
 */
@UntrustedParser
record FetchRefusal(UntrustedText messageId, UntrustedText object) {

    /** {@code error: object <id>: <msg-id>: <detail>} — the id and the message id are captured. */
    private static final Pattern OBJECT_LINE =
            Pattern.compile("^error: object ([0-9a-f]{4,64}): ([A-Za-z]+): ", Pattern.MULTILINE);

    /** The summary {@code index-pack} prints after the per-object lines. */
    private static final String SUMMARY = "fsck error in packed object";

    private static final String SUMMARY_LINE = "fatal: " + SUMMARY;

    /**
     * The refusal as one factory-composed clause every consumer's report quotes — "object {@code
     * <id>} failed validation check {@code <msg-id>}", or the summary's wording alone when git named
     * no object — so the four sites word it once. Both fields leave their carrier through the log
     * exit here; the clause is the factory's own prose and is minted as such.
     */
    UntrustedText refusedObjectClause() {
        if (object.isBlank()) {
            return UntrustedText.factory("git reported '" + messageId.forLog() + "' and named no object");
        }
        return UntrustedText.factory("object " + object.forLog() + " failed validation check " + messageId.forLog());
    }

    /**
     * Reads a failed transfer's stderr for a validation refusal.
     *
     * @param stderr the transfer's captured stderr; never null
     * @return the refusal, naming the first refused object when git listed one; empty for every
     *     other failure (a non-fast-forward rejection, an unreachable daemon, a missing ref, an
     *     unanswerable prompt), which stays the caller's to classify
     */
    static Optional<FetchRefusal> parse(UntrustedText stderr) {
        String text = stderr.forParsing();
        Matcher line = OBJECT_LINE.matcher(text);
        if (line.find()) {
            return Optional.of(
                    new FetchRefusal(UntrustedText.subprocess(line.group(2)), UntrustedText.subprocess(line.group(1))));
        }
        if (text.contains(SUMMARY_LINE)) {
            return Optional.of(new FetchRefusal(UntrustedText.subprocess(SUMMARY), UntrustedText.subprocess("")));
        }
        return Optional.empty();
    }
}
