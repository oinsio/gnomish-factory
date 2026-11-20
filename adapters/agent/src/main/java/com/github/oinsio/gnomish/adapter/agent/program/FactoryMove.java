package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Which difference between the views before and after a {@code verdict} or {@code decision} run is
 * the factory's own pending commit, not a change of the run (design D21 of
 * migrate-verify-to-executor-contract, "The lag the write leaves behind", as amended by review r2 of
 * 2026-11-12; wire-contract "The factory's own pending commit is not a change of the run").
 *
 * <ul>
 *   <li><b>The move, by identity.</b> When {@code branch.oid} moved, the move is the factory's only
 *       when the {@code branch.oid} after the run is the round's last registered commit at the
 *       moment of the judgement. A round with nothing registered has no factory move. No content is
 *       compared (crash-consistency item 13), so every commit of the run is refused — an empty one
 *       and an edit-and-revert pair included: it either lands before the pending commit, which then
 *       fails its compare-and-swap and leaves {@code HEAD} on the run's commit, or above it.
 *   <li><b>The lag.</b> Across a factory move only, the {@code state.json} entry counts as lag when
 *       the index's and the working tree's copies are what they were before the run: the entry
 *       before, when the view before had one (an earlier pending commit's lag); otherwise the index
 *       object is {@code <before>:.gnomish-task/state.json}, read through {@code stateBlobAt}, and
 *       the working tree equals the index. Every other difference still refuses.
 * </ul>
 *
 * <p>The same rule answers a second query, over one view: whether a view taken at the adoption of a
 * recorded key is the commit the factory put the working copy on and nothing but its lag (design D14
 * as amended 2026-11-13; wire-contract "A recorded key is adopted only over an unchanged working
 * copy"). {@code HEAD} must equal the expected commit — equality, never membership of the round's
 * chain — and the one other entry allowed is {@code state.json} with the working tree equal to the
 * index, mode included, and the index object the {@code state.json} of a commit of the round's
 * registered chain, read per listed commit through {@code stateBlobAt}; the chain is never walked here.
 *
 * <p>Implements FR20, FR3, FR11 of migrate-verify-to-executor-contract; NFR-S4 of
 * define-executor-contract.
 */
final class FactoryMove {

    private static final String OID = "# branch.oid ";

    private static final String STATE = EnvelopePaths.STATE_JSON_PATH;

    private FactoryMove() {}

    /**
     * The paths the run changed.
     *
     * @param before the view before the run; never null
     * @param after the view after the run; never null
     * @param registered the round's last registered commit, read now; empty when it has none
     * @param stateBlobAt the {@code state.json} object a commit carries, empty when unreadable
     * @return the changed paths, empty when the run changed nothing; never null
     */
    static List<String> changed(
            Set<String> before,
            Set<String> after,
            Optional<String> registered,
            Function<String, Optional<String>> stateBlobAt) {
        Optional<String> headBefore = header(before);
        Optional<String> headAfter = header(after);
        if (headBefore.equals(headAfter) || headAfter.isEmpty() || !headAfter.equals(registered)) {
            return StatusEntries.changedPaths(before, after);
        }
        Set<String> was = withoutHeader(before);
        Set<String> is = withoutHeader(after);
        Optional<String> lagged = entryFor(after);
        Optional<String> earlier = entryFor(before);
        if (lagged.isPresent() && lags(earlier, lagged.get(), headBefore, stateBlobAt)) {
            is.remove(lagged.get());
            earlier.ifPresent(was::remove);
        }
        return StatusEntries.changedPaths(was, is);
    }

    private static boolean lags(
            Optional<String> earlier,
            String entry,
            Optional<String> headBefore,
            Function<String, Optional<String>> stateBlobAt) {
        String[] now = entry.split(" ", 9);
        if (earlier.isPresent()) {
            String[] was = earlier.get().split(" ", 9);
            // sub, index mode, worktree mode, index object and the working tree's column, as before
            return now[2].equals(was[2])
                    && now[4].equals(was[4])
                    && now[5].equals(was[5])
                    && now[7].equals(was[7])
                    && now[1].charAt(1) == was[1].charAt(1);
        }
        // No entry before: the index and the working tree were the commit's, mode included.
        return lagsOn(
                now,
                blob -> headBefore.flatMap(stateBlobAt).filter(blob::equals).isPresent());
    }

    /**
     * Why a view taken at the adoption of a recorded key is not adoptable, if it is not.
     *
     * @param view the view taken at the adoption; never null
     * @param expected the commit the factory put the working copy on; never blank
     * @param chain the round's registered chain, last registered first; never null
     * @param stateBlobAt the {@code state.json} object a commit carries, empty when unreadable
     * @return the refusal's cause, naming {@code HEAD} or the changed paths; empty when adoptable
     */
    static Optional<String> unadoptable(
            Set<String> view, String expected, List<String> chain, Function<String, Optional<String>> stateBlobAt) {
        Optional<String> head = header(view);
        if (!head.equals(Optional.of(expected))) {
            return Optional.of("HEAD is "
                    + head.map(h -> UntrustedText.subprocess(h).excerpt(128)).orElse("unknown")
                    + ", not the commit the factory put the working copy on (" + expected + ")");
        }
        Set<String> rest = withoutHeaders(view);
        Optional<String> lagged = entryFor(rest);
        if (rest.size() == 1
                && lagged.isPresent()
                && lagsOn(
                        lagged.get().split(" ", 9),
                        blob -> chain.stream()
                                .map(stateBlobAt)
                                .flatMap(Optional::stream)
                                .anyMatch(blob::equals))) {
            rest.clear();
        }
        return rest.isEmpty()
                ? Optional.empty()
                : Optional.of("the working copy holds changes against HEAD other than the factory's state.json lag: "
                        + WorkingCopyRefusal.named(StatusEntries.changedPaths(Set.of(), rest)));
    }

    /** A {@code state.json} entry whose working tree equals its index, mode included, on an index object {@code blobOk}. */
    private static boolean lagsOn(String[] now, Predicate<String> blobOk) {
        return now[1].charAt(1) == '.' && now[3].equals(now[4]) && now[4].equals(now[5]) && blobOk.test(now[7]);
    }

    /** The one ordinary entry for {@code state.json}, if the view has it. */
    private static Optional<String> entryFor(Set<String> view) {
        return view.stream()
                .filter(e -> e.startsWith("1 ") && e.split(" ", 9).length == 9 && e.split(" ", 9)[8].equals(STATE))
                .findFirst();
    }

    private static Optional<String> header(Set<String> view) {
        return view.stream()
                .filter(e -> e.startsWith(OID))
                .map(e -> e.substring(OID.length()))
                .findFirst();
    }

    private static Set<String> withoutHeader(Set<String> view) {
        Set<String> kept = new LinkedHashSet<>(view);
        kept.removeIf(e -> e.startsWith(OID));
        return kept;
    }

    /** The view without its {@code branch.oid} and {@code branch.head} headers. */
    private static Set<String> withoutHeaders(Set<String> view) {
        Set<String> kept = new LinkedHashSet<>(view);
        kept.removeIf(e -> e.startsWith("# "));
        return kept;
    }
}
