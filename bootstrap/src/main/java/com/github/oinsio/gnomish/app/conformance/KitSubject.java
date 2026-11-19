package com.github.oinsio.gnomish.app.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import java.util.List;
import java.util.Map;
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
     * The settings the kit sends in the subject's ordinary cases (design D18 of
     * migrate-verify-to-executor-contract): empty for a program, whose declaration the command builds
     * with the pending settings only; an in-process built-in's own, which its settings-schema case
     * judges too.
     *
     * @return the settings; never null, possibly empty
     */
    Map<String, Object> settings();

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
     * One case's run: the executor to drive, the working copy its requests name, the binding its polls
     * are sent through, and what the wire observation judged of the documents this run wrote.
     */
    interface CaseRun extends AutoCloseable {

        /** The executor of this case's {@code start}; never null. */
        Executor executor();

        /** The working copy the case's {@code start} names; its path ends in the case's name. */
        Workspace workspace();

        /**
         * The binding every poll of this run is sent through (design D11 of
         * migrate-verify-to-executor-contract as amended 2026-11-14): an executor bound to a working
         * copy other than {@code start}'s, at {@code <scratch>/poll/<case>}, made on the first call and
         * the same one after it, whose content follows {@code role} ({@link PollCopy}). A program's poll
         * executor answers from the pending table its {@code start} filled, as one run of the engine
         * holds one.
         *
         * @param role the role the case's {@code start} ran in; never null
         * @return the poll binding; never null
         */
        PollBinding poll(Role role);

        /**
         * Every schema breach of the documents written so far in this run, through {@code start}'s
         * working copy and then through the poll's; never null.
         */
        List<WireBreach> violations();

        /** Releases the case's environments; never throws for a run that already ended. */
        @Override
        void close();
    }

    /**
     * The poll side of a case's run.
     *
     * @param executor the executor every poll of the run is sent to; never null
     * @param workspace the working copy every poll names; its path ends in the case's name
     */
    record PollBinding(Executor executor, Workspace workspace) {}
}
