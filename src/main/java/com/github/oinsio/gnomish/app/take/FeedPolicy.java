package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Eligibility policy for claiming from a {@code listReady} feed, shared by
 * {@code serve} and bare auto {@code take} (design D2): applies the existing
 * abort-backoff filter ({@link BackoffPolicy#filterEligible}), orders
 * returned tasks ahead of fresh ones (FR6 — returned tasks are claimable
 * always, outside the WIP limit), admits fresh tasks only while
 * {@code openFrontCount < wipLimit} (FR6), and applies a head-zone pick
 * (design D4): a uniformly random draw among the first {@link #HEAD_ZONE_K}
 * eligible entries becomes the first claim candidate, with the remaining
 * eligible entries following in their original relative order for
 * claim-race fallthrough (FR9).
 *
 * <p>This class is pure logic — like {@link BackoffPolicy}, it takes
 * {@code openFrontCount} and {@code wipLimit} as explicit parameters rather
 * than reading the tracker or {@code factory.tracker.wip-limit} itself.
 * Supplying a fresh {@code openFrontCount} before every claim attempt (the
 * per-claim re-check of design D5) and reading {@code wipLimit} from
 * configuration are the caller's job.
 *
 * <p>Implements FR6, FR9, NFR-C1, D2, D4 of add-factory-serve — the WIP
 * gate that drops fresh entries once {@code openFrontCount >= wipLimit} is
 * what caps the tokens a runaway queue can burn.
 */
public final class FeedPolicy {

    /** Head-zone width per design D4: a fixed constant, not configuration. */
    public static final int HEAD_ZONE_K = 5;

    /**
     * Caps how many head-of-queue entries one {@code listReady} feed read returns, shared by bare
     * auto {@code take} and every {@code serve} feed cycle (design D2). In the common case only
     * the first entry is ever claimed; the rest exist to give the claim-race/backoff fallback walk
     * enough candidates without a second tracker round-trip. No spec or design fixes a number: 20
     * comfortably covers "several tasks concurrently backed off or raced" without over-fetching
     * from the adapter on every read.
     */
    public static final int FEED_LIMIT = 20;

    private FeedPolicy() {}

    /**
     * Computes the ordered list of claim candidates for one feed cycle.
     *
     * <p>Order: {@code readyTasks} is backoff-filtered (D10), split into
     * returned and fresh preserving each group's relative adapter order,
     * fresh entries are dropped entirely when {@code openFrontCount >=
     * wipLimit} (FR6), and the two groups are concatenated returned-first.
     * A head-zone pick (D4) is then applied over the first
     * {@code min(HEAD_ZONE_K, size)} entries of that combined ordering: one
     * entry is drawn uniformly at random via {@code random} and moved to the
     * front; every other entry keeps its original relative order behind it,
     * so a caller that falls through the list on a lost claim race
     * reproduces today's oldest-first-soft-preference behavior (FR9).
     *
     * @param readyTasks the adapter's {@code listReady} result, in queue
     *     order; never null
     * @param base the backoff base for a single abort; never null
     * @param cap the maximum backoff delay; never null
     * @param now the instant to evaluate backoff against; never null
     * @param openFrontCount the current count of open fronts
     *     ({@code Working} + {@code AwaitingHuman}), supplied fresh by the
     *     caller (design D5's per-claim re-check is the caller's
     *     responsibility, not this method's)
     * @param wipLimit the configured WIP limit W; fresh tasks are eligible
     *     only while {@code openFrontCount < wipLimit}
     * @param random the source of randomness for the head-zone pick; a
     *     seeded instance makes selection deterministic for tests
     * @return the ordered claim-candidate list — empty iff nothing is
     *     eligible after backoff filtering and the WIP gate; never null
     */
    public static List<ReadyTask> selectClaimCandidates(
            List<ReadyTask> readyTasks,
            Duration base,
            Duration cap,
            Instant now,
            int openFrontCount,
            int wipLimit,
            Random random) {
        List<ReadyTask> eligible = BackoffPolicy.filterEligible(readyTasks, base, cap, now);
        List<ReadyTask> ordered = returnedFirstThenFresh(eligible, openFrontCount, wipLimit);
        return headZonePick(ordered, random);
    }

    private static List<ReadyTask> returnedFirstThenFresh(List<ReadyTask> eligible, int openFrontCount, int wipLimit) {
        List<ReadyTask> returned = new ArrayList<>();
        List<ReadyTask> fresh = new ArrayList<>();
        for (ReadyTask task : eligible) {
            if (task.returned()) {
                returned.add(task);
            } else {
                fresh.add(task);
            }
        }
        List<ReadyTask> combined = new ArrayList<>(returned);
        if (openFrontCount < wipLimit) {
            combined.addAll(fresh);
        }
        return combined;
    }

    private static List<ReadyTask> headZonePick(List<ReadyTask> ordered, Random random) {
        if (ordered.isEmpty()) {
            return List.of();
        }
        int zoneWidth = Math.min(HEAD_ZONE_K, ordered.size());
        int pickIndex = random.nextInt(zoneWidth);

        List<ReadyTask> result = new ArrayList<>(ordered.size());
        result.add(ordered.get(pickIndex));
        for (int i = 0; i < ordered.size(); i++) {
            if (i != pickIndex) {
                result.add(ordered.get(i));
            }
        }
        return result;
    }
}
