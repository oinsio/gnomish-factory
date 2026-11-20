package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.SarifLevel;
import com.github.oinsio.gnomish.executorcontract.SarifResult;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * How a built-in verdict executor answers with more results than one verdict may carry ({@link
 * Answer.Verdict#MAX_RESULTS}): it never drops them — a dropped list would pass a check its results
 * fail — but lists {@code MAX_RESULTS - 1} and adds one result standing for the rest, so the answer
 * fits the cap and the check still fails as a judgement (human decision 2026-11-10, task 3.1a). The
 * {@code files-exist} probe takes the count ({@link #listed}) and words its own last result; a
 * {@code shell} check's findings file is capped here whole ({@link #shell}).
 *
 * <p>Implements FR1, FR5, FR7 of migrate-verify-to-executor-contract.
 */
final class ResultCap {

    private ResultCap() {}

    /**
     * How many of {@code count} results an answer lists: all of them while they fit the cap,
     * otherwise {@code MAX_RESULTS - 1}, the last result standing for the rest.
     *
     * @param count how many results there are
     * @return how many are listed; never more than the cap
     */
    static int listed(int count) {
        return count <= Answer.Verdict.MAX_RESULTS ? count : Answer.Verdict.MAX_RESULTS - 1;
    }

    /**
     * The message of the one result that stands for the results a {@code shell} answer does not list.
     *
     * @param count how many results are not listed
     * @return the message; never null
     */
    static String moreResults(int count) {
        return "and " + count + " more results not listed";
    }

    /**
     * A {@code shell} check's results within the cap: all of them while they fit; above it the most
     * severe {@code MAX_RESULTS - 1}, in the order written, and one result for the rest at the most
     * severe level among them — so no result the cap drops is more severe than one it keeps, and the
     * verdict the engine derives under every threshold is the one the whole list states (FR7).
     *
     * @param results the results, in the order written; never null
     * @return the capped results; never null
     */
    static List<ShellResult> shell(List<ShellResult> results) {
        int listed = listed(results.size());
        if (listed == results.size()) {
            return results;
        }
        // A stable sort: among results of one level the earlier written is kept.
        List<Integer> bySeverity = IntStream.range(0, results.size())
                .boxed()
                .sorted(Comparator.comparing(
                                (Integer i) -> results.get(i).sarif().level())
                        .reversed())
                .toList();
        List<ShellResult> capped = new ArrayList<>();
        bySeverity.subList(0, listed).stream().sorted().forEach(i -> capped.add(results.get(i)));
        SarifLevel rest = results.get(bySeverity.get(listed)).sarif().level();
        capped.add(new ShellResult(
                new SarifResult(null, rest, UntrustedText.factory(moreResults(results.size() - listed)), null, null),
                null));
        return capped;
    }
}
