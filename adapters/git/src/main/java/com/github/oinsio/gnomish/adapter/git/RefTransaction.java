package com.github.oinsio.gnomish.adapter.git;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One {@code git update-ref --stdin} transaction as a value: the refs it moves, each to its new
 * commit, the first under a compare-and-swap against the tip it was read at (design D13 of
 * tighten-spec-contract, amended 2026-10-13). {@code prepare} locks every ref and checks every
 * precondition before {@code commit} writes any, so the refs land together or none does — the
 * transaction is one durable step, with no kill window between its refs.
 *
 * <p>Built by {@link FactoryRegister} alone, the one owner of a task branch's and its register's
 * moves in the factory clone; run by the git runner's typed entry, {@link GitRunner#run(java.nio.file.Path,
 * RefTransaction)}, which hands {@link #script()} to the process's standard input.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 *
 * @param updates the ref moves, in order; never empty
 */
record RefTransaction(List<Update> updates) {

    /** The argv of every transaction: the script travels on standard input, never in argv. */
    static final List<String> ARGV = List.of("update-ref", "--stdin");

    /**
     * One ref move.
     *
     * @param ref the full ref name
     * @param newCommit the commit the ref names afterwards; all zeros deletes the ref
     * @param expectedTip the commit the ref must name now, or {@code null} for no precondition
     */
    record Update(String ref, String newCommit, @Nullable String expectedTip) {

        /** One full ref name: under {@code refs/}, with no space, control character or DEL in it. */
        private static final Pattern FULL_REF = Pattern.compile("refs/[^\\x00-\\x20\\x7f]+");

        /**
         * Refuses any value that is not one full ref name or one full object id: the script is
         * line-oriented and space-separated, so a value carrying a separator would write a command
         * of its own. The values are not quoted in the message — they may be git's output.
         */
        Update {
            if (!FULL_REF.matcher(ref).matches()) {
                throw new IllegalArgumentException(
                        "the ref of a ref-transaction update is not one full ref name under refs/");
            }
            if (!VerifiedTip.isObjectId(newCommit)) {
                throw new IllegalArgumentException(
                        "the new commit of a ref-transaction update is not one full object id");
            }
            if (expectedTip != null && !VerifiedTip.isObjectId(expectedTip)) {
                throw new IllegalArgumentException(
                        "the expected tip of a ref-transaction update is not one full object id");
            }
        }

        /** Deletes {@code ref} if it still names {@code expectedTip}: update-ref reads an all-zero new value as a deletion. */
        static Update deletion(String ref, String expectedTip) {
            return new Update(ref, "0".repeat(expectedTip.length()), expectedTip);
        }
    }

    RefTransaction {
        if (updates.isEmpty()) {
            throw new IllegalArgumentException("a ref transaction moves at least one ref");
        }
        updates = List.copyOf(updates);
    }

    /** The transaction's standard input: {@code start}, one {@code update} per ref, then the two-phase close. */
    byte[] script() {
        StringBuilder script = new StringBuilder("start\n");
        for (Update update : updates) {
            script.append("update ").append(update.ref()).append(' ').append(update.newCommit());
            if (update.expectedTip() != null) {
                script.append(' ').append(update.expectedTip());
            }
            script.append('\n');
        }
        return script.append("prepare\ncommit\n").toString().getBytes(StandardCharsets.UTF_8);
    }
}
