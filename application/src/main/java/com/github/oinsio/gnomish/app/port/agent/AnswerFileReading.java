package com.github.oinsio.gnomish.app.port.agent;

import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * What the one answer read ({@link AnswerFileRead}) made of an answer file: nothing written, a
 * contract answer, or a file refused with its cause. There is no fourth outcome: content that is not
 * a valid answer is refused, never turned into a question (design D15 of extract-agent-round-runner,
 * part 4). What a refused reading means for the round is decided by one mapping the live round and
 * the recovery of a snapshot both call, never by the reader.
 *
 * <p>Implements FR21, NFR-R5 of extract-agent-round-runner.
 */
public sealed interface AnswerFileReading {

    /** No answer file was written: the executor answered nothing. */
    record Absent() implements AnswerFileReading {}

    /**
     * The file holds a contract answer that keeps its invariants.
     *
     * @param answer the answer, its texts minted in the writer's family; never null
     */
    record Answered(Answer answer) implements AnswerFileReading {}

    /**
     * The file cannot be taken as an answer: not JSON, empty, over the cap, or refused by the
     * contract's answer reading.
     *
     * @param cause the factory's sentence naming what is wrong, any quoted content already through an
     *     exit; never blank
     * @param written what the read took of the file — whole, or cut at the cap and marked — in the
     *     writer's family; never null, possibly blank
     */
    record Refused(String cause, UntrustedText written) implements AnswerFileReading {}
}
