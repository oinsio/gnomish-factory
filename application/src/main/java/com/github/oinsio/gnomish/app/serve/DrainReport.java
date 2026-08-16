package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TakeResultDescription;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * A drain run's closing report (FR10, NFR-O2, M3): a thread-safe sink that every finishing slot
 * records its terminal {@link TakeResult} into, and the human-readable "what it worked" summary
 * {@code ServeCommand} logs once {@link FeedAutomaton#drain()} returns and every slot is empty.
 * Deliberately proportionate to what drain mode needs — an operator-readable log line, not the
 * machine-findable structured summary batch mode (a later task) will require.
 *
 * <p>{@link TakeSlotRunner} is optionally attached one of these (see {@link
 * TakeSlotRunner#attachDrainReport(DrainReport)}), so only a drain run ever writes into it; an
 * ordinary {@code serve} run never attaches one, and no report is built or logged.
 *
 * <p>Implements FR10, NFR-O2, M3 of add-factory-serve.
 */
public final class DrainReport {

    /** One worked task's identity and a short human-readable outcome description. */
    public record Entry(TaskRef ref, String outcome) {}

    private final ConcurrentLinkedQueue<Entry> entries = new ConcurrentLinkedQueue<>();

    /**
     * Records {@code result} against {@code ref}, converting it to a short outcome description.
     * Safe to call from any number of concurrently-finishing slot threads.
     *
     * @param ref the task whose slot just reached a terminal result; never null
     * @param result the terminal result the slot reached; never null
     */
    public void record(TaskRef ref, TakeResult result) {
        entries.add(new Entry(ref, TakeResultDescription.describe(result)));
    }

    /** The recorded entries, in the order slots finished; possibly empty; never null. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * A one-line, operator-readable closing summary (NFR-O2, M3): "drain worked 0 task(s)" for an
     * empty run, else every entry named as {@code ref -> outcome}.
     */
    public String summary() {
        List<Entry> snapshot = entries();
        if (snapshot.isEmpty()) {
            return "drain worked 0 task(s)";
        }
        String named = snapshot.stream()
                .map(entry -> entry.ref().id() + " -> " + entry.outcome())
                .collect(Collectors.joining(", "));
        return "drain worked " + snapshot.size() + " task(s): " + named;
    }
}
