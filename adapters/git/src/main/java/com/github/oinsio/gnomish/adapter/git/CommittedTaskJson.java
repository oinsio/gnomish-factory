package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The tip's {@code task.json} as a lifecycle rewrite reads it: the document's text exactly as
 * committed, and the DTO parsed from it. Both media read the envelope this way — {@link
 * RequiredTaskJson} for the worktree's {@code HEAD}, {@link TaskLifecycleCommitWriter} for a bare
 * branch tip — so the rewrite that follows can carry every field forward through the DTO and still
 * ask the one question only the committed text answers: whether the document it is about to commit
 * is already there.
 *
 * <p>That question is {@link #carries}, the single owner of the "unchanged, so no commit" decision
 * of {@code recordOutcome} (design D8 of make-run-headless, "Idempotence"; crash-consistency item
 * 8). A park re-recorded identically — a {@code --resume} without {@code --decision} over an {@code
 * AttemptsExhausted} park whose rerun exhausts the same limit — must leave the tip as it is rather
 * than fail on git's "nothing to commit", and both media decide that here, before any commit step,
 * so no failing subprocess is ever involved. The comparison is byte for byte: a document that
 * differs only in formatting commits once more, which is harmless, while a semantic difference can
 * never be mistaken for sameness.
 *
 * <p>The text component is named {@code committedText}, not {@code text}: a bare {@code text()}
 * accessor would be a carrier here and a plain {@code String} on other owners, which puts the name
 * out of reach of the untrusted-text sink scan (rule (c), design D2 of type-untrusted-text). A name
 * no {@code String} twin answers to keeps the scan decisive.
 *
 * <p>Implements FR10 of make-run-headless (task 2.6).
 *
 * @param committedText the committed document, as read off the tip
 * @param dto the same document, parsed and version-gated by {@link TaskJsonMapper#readDto}
 */
record CommittedTaskJson(UntrustedText committedText, TaskJsonDto dto) {

    /**
     * Parses and version-gates a document read off a branch tip, keeping its text beside the DTO.
     *
     * @param text the {@code task.json} text as it was read off the branch tip; never null
     * @return the committed document with its parsed DTO
     */
    static CommittedTaskJson parse(UntrustedText text) {
        return new CommittedTaskJson(text, TaskJsonMapper.readDto(text));
    }

    /**
     * Whether the committed document already is {@code serialized}, byte for byte — compared as
     * carriers, so no raw text leaves either side.
     *
     * @param serialized the rewritten document about to be committed
     * @return {@code true} when the tip already carries exactly this document
     */
    boolean carries(String serialized) {
        return committedText.equals(UntrustedText.branchDocument(serialized));
    }
}
