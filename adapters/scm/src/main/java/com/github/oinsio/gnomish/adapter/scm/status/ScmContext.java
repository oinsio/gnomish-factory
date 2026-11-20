package com.github.oinsio.gnomish.adapter.scm.status;

/**
 * One report of a named verdict on the attempt SHA, as the platform listed it (FR4 of
 * migrate-verify-to-executor-contract): a check run or a commit status, its context name, where it
 * stands, the platform's own sequence number for it — a later report of the same context in the
 * same source carries a larger one — and the platform's words about it.
 *
 * <p>Implements FR4 of migrate-verify-to-executor-contract.
 */
final class ScmContext {

    /** The two listings a context is read from; sequence numbers compare within one only. */
    enum Source {
        CHECK_RUN,
        STATUS
    }

    private final Source source;
    private final String name;
    private final ScmContextState state;
    private final long sequence;
    private final String detail;

    /**
     * @param source the listing the report came from
     * @param name the context name, the check's {@code expected} entry it answers
     * @param state where it stands
     * @param sequence the platform's id of the report, larger for a later report
     * @param detail the platform's words about the report — its state and description — as it wrote
     *     them; minted in the tracker family where a result quotes them
     */
    ScmContext(Source source, String name, ScmContextState state, long sequence, String detail) {
        this.source = source;
        this.name = name;
        this.state = state;
        this.sequence = sequence;
        this.detail = detail;
    }

    Source source() {
        return source;
    }

    String name() {
        return name;
    }

    ScmContextState state() {
        return state;
    }

    long sequence() {
        return sequence;
    }

    String detail() {
        return detail;
    }
}
