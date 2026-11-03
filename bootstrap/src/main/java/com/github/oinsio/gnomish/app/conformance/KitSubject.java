package com.github.oinsio.gnomish.app.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The candidate as the conformance kit's core sees it (FR12, FR24 of define-executor-contract;
 * design D2, D19): an {@link Executor} per case, plus the verdicts of whatever watched the wire. A
 * program is one subject ({@link ProgramCandidate}: one process per operation, each document judged
 * against its schema file); a built-in driven in process is another, with no wire, so the cases that
 * exist only on the wire do not apply to it ({@link KitCase#wireOnly()}).
 *
 * <p>Implements FR8, FR12, FR24 of define-executor-contract.
 */
@NullMarked
public interface KitSubject {

    /**
     * Whether the subject's documents and processes are on a wire the kit can watch and shape.
     *
     * @return {@code false} for an executor driven in process
     */
    boolean onWire();

    /**
     * Describes the candidate once, as the factory would before its first round.
     *
     * @return the answer, the document it was read from and what was judged of it; never null
     */
    Described describe();

    /**
     * A fresh run of one case: an executor of its own, in a working copy named after the case.
     *
     * @param kase the case to run; never null
     * @param describe the answer {@link #describe()} obtained; never null
     * @return the case's run, closed by the caller when the case is decided; never null
     */
    CaseRun open(KitCase kase, Describe describe);

    /**
     * What describing the candidate yielded.
     *
     * @param answer the describe answer the factory accepted, or {@code null} when it refused it
     * @param document the describe document as written, parsed as a plain JSON tree, or {@code null} when
     *     none was written or it is no JSON
     * @param violations the describe document's breaches of its schema file; never null
     * @param refusals why the factory refused the answer, in its own words; empty when it accepted it
     */
    record Described(
            @Nullable Describe answer,
            @Nullable JsonNode document,
            List<WireBreach> violations,
            List<String> refusals) {

        public Described {
            violations = List.copyOf(violations);
            refusals = List.copyOf(refusals);
        }
    }

    /**
     * One case's run: the executor to drive, the working copy its requests name, and what the wire
     * observation judged of the documents this run wrote.
     */
    interface CaseRun extends AutoCloseable {

        /** The executor of this case alone; never null. */
        Executor executor();

        /** The working copy the case's requests name; its path ends in the case's name. */
        Workspace workspace();

        /** Every schema breach of the documents written so far in this run; never null. */
        List<WireBreach> violations();

        /** Releases the case's environment; never throws for a run that already ended. */
        @Override
        void close();
    }
}
