package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * What a dialect did with a round's final answer (FR2, FR3, FR4 of extract-agent-round-runner):
 * wrote it to the answer file, wrote none, or found it malformed. The runner reads the answer file
 * through the shared result-file reader in every case but {@link Malformed}, which it reports as a
 * malformed answer — never as a pass.
 *
 * <p>Implements FR2, FR4 of extract-agent-round-runner.
 */
public sealed interface DialectAnswer {

    /** The dialect extracted the answer from the stream and wrote it to the answer file. */
    record Written() implements DialectAnswer {}

    /**
     * The dialect wrote no answer: the role has none, or the agent wrote the answer file itself.
     */
    record NotWritten() implements DialectAnswer {}

    /**
     * The stream's final output does not match the answer's schema.
     *
     * @param reason why, in the family of the text it judged; never null
     */
    record Malformed(UntrustedText reason) implements DialectAnswer {}
}
