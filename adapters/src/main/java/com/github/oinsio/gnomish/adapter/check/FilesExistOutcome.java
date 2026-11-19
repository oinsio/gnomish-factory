package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What one {@code files-exist} probe found, before the executor renders it (FR1 of
 * migrate-verify-to-executor-contract): the paths that are missing — none when every path exists — or
 * the reason no existence could be judged. The {@link FilesExistCheckRunner} judges it once and the
 * {@link FilesExistExecutor} renders it as the executor contract's result.
 *
 * <p>The {@code error} results a missing list is reported as are listed here, by {@link
 * #reported}: the {@code files} setting has no count limit, and above the contract's cap on one verdict's results ({@link
 * Answer.Verdict#MAX_RESULTS}) the last result names how many more paths are missing (human
 * decision 2026-11-10, task 3.1a) — the check still fails as a judgement.
 *
 * <p>Implements FR1, D5 of migrate-verify-to-executor-contract.
 */
sealed interface FilesExistOutcome {

    /**
     * The message of the one {@code error} result a missing path is reported as.
     *
     * @param path the missing path, as the settings list it
     * @return the message; never null
     */
    static String message(String path) {
        return "missing file: " + path;
    }

    /**
     * The message of the one {@code error} result that stands for the missing paths the answer does
     * not list, above the result cap.
     *
     * @param count how many missing paths are not listed
     * @return the message; never null
     */
    static String moreMissing(int count) {
        return "and " + count + " more missing files not listed";
    }

    /**
     * The {@code error} results {@code missing} is reported as: one per path while they fit the
     * contract's result cap; above it, the first {@code MAX_RESULTS - 1} paths and one result naming
     * how many more are missing, so the answer never exceeds the cap.
     *
     * @param missing the missing paths, in the order the settings list them; possibly empty
     * @return the results, in that order; never null
     */
    static List<Reported> reported(List<String> missing) {
        int listed = missing.size() <= Answer.Verdict.MAX_RESULTS ? missing.size() : Answer.Verdict.MAX_RESULTS - 1;
        List<Reported> results = new ArrayList<>(listed + 1);
        for (String path : missing.subList(0, listed)) {
            results.add(new Reported(message(path), path));
        }
        if (listed < missing.size()) {
            results.add(new Reported(moreMissing(missing.size() - listed), null));
        }
        return results;
    }

    /**
     * One {@code error} result of a missing list, before the executor renders it.
     *
     * @param message the result's message; never null
     * @param path the missing path it names, or {@code null} for the result naming the unlisted rest
     */
    record Reported(String message, @Nullable String path) {}

    /**
     * The existence check ran: these listed paths are missing.
     *
     * @param paths the missing paths, in the order the settings list them; possibly empty
     */
    record Missing(List<String> paths) implements FilesExistOutcome {}

    /**
     * No existence could be judged: malformed settings, a path escaping the workspace, or a
     * workspace the probe cannot read.
     *
     * @param reason why; never null
     */
    record Unanswerable(UntrustedText reason) implements FilesExistOutcome {}
}
