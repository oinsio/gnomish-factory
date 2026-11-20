package com.github.oinsio.gnomish.adapter.scm.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The named verdicts the SCM holds for one attempt SHA, latest per context (FR4, NFR-R2 of
 * migrate-verify-to-executor-contract; design D3, D9): within one listing the report with the
 * largest platform sequence wins, so a context that reported a failure and then a success counts as
 * passed; across the two listings — a check run and a commit status under the same name — the
 * state further from a pass wins ({@link ScmContextState}), since their sequences are not
 * comparable and neither may hide the other's failure or keep-waiting. It also carries how many
 * reports the SCM counted on the SHA and the read did not list — past the reader's page bound, or
 * a listing shorter than its own total — so an absent context can say it may be among them.
 *
 * <p>Implements FR4, NFR-R2 of migrate-verify-to-executor-contract.
 */
final class ContextVerdicts {

    private final Map<String, ScmContext> latest;
    private final int unread;

    private ContextVerdicts(Map<String, ScmContext> latest, int unread) {
        this.latest = Map.copyOf(latest);
        this.unread = unread;
    }

    /**
     * Takes the latest verdict per context.
     *
     * @param reports every report read for the SHA, from both listings, in any order
     * @param unread the reports the listings counted and the read did not list; zero or less when
     *     none
     * @return the latest verdict per context
     */
    static ContextVerdicts of(List<ScmContext> reports, int unread) {
        Map<String, ScmContext> checkRuns = latestIn(reports, ScmContext.Source.CHECK_RUN);
        Map<String, ScmContext> merged = new HashMap<>(latestIn(reports, ScmContext.Source.STATUS));
        checkRuns.forEach((name, run) -> merged.merge(name, run, ContextVerdicts::furtherFromPass));
        return new ContextVerdicts(merged, unread);
    }

    /** The reports the SCM counted on the SHA that the read did not list; zero or less when none. */
    int unread() {
        return unread;
    }

    /**
     * Returns the latest verdict of one context.
     *
     * @param name an expected context
     * @return its latest verdict, or empty when the SCM holds none for the SHA
     */
    Optional<ScmContext> of(String name) {
        return Optional.ofNullable(latest.get(name));
    }

    private static Map<String, ScmContext> latestIn(List<ScmContext> reports, ScmContext.Source source) {
        Map<String, ScmContext> latest = new HashMap<>();
        for (ScmContext report : reports) {
            if (report.source() == source) {
                latest.merge(report.name(), report, (held, next) -> next.sequence() > held.sequence() ? next : held);
            }
        }
        return latest;
    }

    private static ScmContext furtherFromPass(ScmContext status, ScmContext run) {
        return run.state().compareTo(status.state()) > 0 ? run : status;
    }
}
