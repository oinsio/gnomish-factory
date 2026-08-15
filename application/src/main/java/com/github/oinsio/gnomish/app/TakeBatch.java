package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The batch-take scheduler loop (task 6.2 of add-factory-serve, FR3): runs a known, finite list
 * of refs through {@code perRef} up to {@code slots} concurrently, reusing {@link SlotLedger}'s
 * permit-before-work ordering and one virtual thread per in-flight ref — structurally the same
 * scheduling primitive {@code serve}'s feed automaton uses (design D1), but driven over a fixed
 * list instead of {@code listReady} polling: batch never consults readiness or abort backoff
 * (design D6, "the operator mandate overrides the readiness criterion... by never consulting
 * either" — see {@link TakeDisposition}), so {@link com.github.oinsio.gnomish.app.serve.FeedAutomaton}'s
 * poll-driven state machine does not apply here.
 *
 * <p>{@code perRef} is deliberately generic ({@code Function<String, TakeResult>}, not tied to
 * {@link TakeDispatcher}'s collaborators): the scheduling concern (bounded concurrency, order
 * preservation, skip-and-continue) is independent of what running one ref actually does, which
 * keeps this class trivially testable without a tracker, a git repo, or a pipeline.
 *
 * <p>A skip or a refusal is just an ordinary {@code perRef} result (a {@link TakeResult.Skipped}),
 * not a failure of the loop itself — "skips reported and the run continues" (FR3) falls out of
 * this class doing nothing special for any particular {@link TakeResult} variant. An uncaught
 * {@link RuntimeException} from {@code perRef} is a different case (tracker-take spec "Tool
 * failure dominates", task 6.3): left unhandled, it would kill only that ref's virtual thread and
 * leave its slot in {@code results} as {@code null} — silently corrupting the run instead of
 * failing it loudly. {@link #run} instead captures it as a {@link
 * TakeBatchOutcome#toolFailure(String, RuntimeException) tool-failure outcome} so the ref is
 * reported like any other and the other refs still run to completion.
 *
 * <p>Implements FR3, NFR-O2 of add-factory-serve.
 */
final class TakeBatch {

    private static final Logger log = LoggerFactory.getLogger(TakeBatch.class);

    private TakeBatch() {}

    /**
     * Runs every ref in {@code refs} through {@code perRef}, at most {@code slots} at a time.
     *
     * @param refs the raw ref strings to run, in the order the operator listed them; never empty
     * @param slots the instance's concurrency limit N (design D3's {@code factory.serve.slots},
     *     FR2: "the N limit applies to batch and serve"); positive
     * @param perRef runs one raw ref to its terminal {@link TakeResult}; called from a dedicated
     *     virtual thread per ref, so it must be safe to invoke concurrently with itself; an
     *     uncaught {@link RuntimeException} is captured as that ref's own tool-failure outcome
     *     rather than propagating
     * @return one {@link TakeBatchOutcome} per ref, in the same order as {@code refs}
     * @throws InterruptedException if the calling thread is interrupted while waiting for a free
     *     slot or for the in-flight refs to finish
     */
    static List<TakeBatchOutcome> run(List<String> refs, int slots, Function<String, TakeResult> perRef)
            throws InterruptedException {
        SlotLedger ledger = new SlotLedger(slots);
        TakeBatchOutcome[] outcomes = new TakeBatchOutcome[refs.size()];
        List<Thread> inFlight = new ArrayList<>(refs.size());
        for (int i = 0; i < refs.size(); i++) {
            int index = i;
            String rawRef = refs.get(i);
            TaskRef slotKey = new TaskRef("batch-slot-" + index);
            ledger.acquire();
            ledger.assign(slotKey);
            inFlight.add(Thread.ofVirtual().start(() -> {
                try {
                    outcomes[index] = new TakeBatchOutcome(rawRef, perRef.apply(rawRef));
                } catch (RuntimeException ex) {
                    log.warn("batch take: ref '{}' failed with a tool error", rawRef, ex);
                    outcomes[index] = TakeBatchOutcome.toolFailure(rawRef, ex);
                } finally {
                    ledger.release(slotKey);
                }
            }));
        }
        for (Thread thread : inFlight) {
            thread.join();
        }
        return List.of(outcomes);
    }

    /**
     * Wires {@link TakeDispatcher#runOneRef} as the {@link #run} loop's {@code perRef} function for
     * one {@code take} batch invocation, then delegates: shares {@code heartbeat} (so one {@link
     * com.github.oinsio.gnomish.app.lease.ClaimLossFlag} and one beat cover the whole run, exactly
     * as {@link com.github.oinsio.gnomish.app.serve.TakeSlotRunner} shares them across serve's
     * slots) and {@code takeArguments.takeover()} across every ref (design D6: batch {@code
     * --takeover} is whole-run, not per-ref), and forces {@link TakeoverConfirmation#UNAVAILABLE}
     * for every ref — batch is unconditionally non-interactive (FR4): no TTY dialog exists in this
     * path, headless {@code --takeover} is the only authorization.
     *
     * <p>The summary text and aggregate exit code are task 6.3's concern; this returns one {@link
     * TakeBatchOutcome} per ref, in {@code takeArguments.refs()} order, for 6.3 to render.
     *
     * <p>Implements FR3, FR4, D6 of add-factory-serve.
     *
     * @param slots the concurrency limit N (design D3's {@code factory.serve.slots}); positive
     * @throws InterruptedException if the calling thread is interrupted while waiting for a free
     *     slot or for in-flight refs to finish
     */
    static List<TakeBatchOutcome> dispatch(
            TakeDispatcher dispatcher,
            String taskIdMdcKey,
            TakeArguments takeArguments,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            List<String> credentialEnvVarsToScrub,
            TrackerAdapterFactory factory,
            RunAssembly takeAssembly,
            TakeHeartbeat heartbeat,
            int slots)
            throws InterruptedException {
        return run(takeArguments.refs(), slots, rawRef -> {
            try {
                return dispatcher.runOneRef(
                        takeArguments,
                        rawRef,
                        definition,
                        trackerConfig,
                        tracker,
                        instanceId,
                        credentialEnvVarsToScrub,
                        factory,
                        takeAssembly,
                        heartbeat,
                        TakeoverConfirmation.UNAVAILABLE);
            } finally {
                // Each ref runs on its own dedicated virtual thread (this class's run loop), so
                // this clears only that thread's own MDC entry, mirroring TakeSlotRunner's
                // per-slot clear — never the invoking thread's.
                MDC.remove(taskIdMdcKey);
            }
        });
    }
}
