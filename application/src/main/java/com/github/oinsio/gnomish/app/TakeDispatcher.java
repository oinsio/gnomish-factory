package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TaskSummaryAssembler;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import com.github.oinsio.gnomish.status.AnchorLog;
import com.github.oinsio.gnomish.status.TaskSummary;
import com.github.oinsio.gnomish.status.WallTime;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.slf4j.MDC;

/**
 * The explicit-mode and bare-auto dispatch of one {@code gnomish take} invocation, extracted from
 * {@link TakeCommand} for file size. Holds the per-invocation-invariant collaborators — among them
 * the invocation's one {@link SlotWiring}, shared by explicit, bare and batch mode as the heartbeat
 * inside it already is (D2 of introduce-slot-wiring); each dispatch method takes only the
 * run-specific values ({@link Tracker}, definition, tracker config) built by {@link TakeCommand#run}.
 *
 * <p>Implements FR9, FR10, D8, D15, D16 of add-tracker-port; FR4 of introduce-slot-wiring.
 */
record TakeDispatcher(
        SlotWiring wiring,
        FactoryProperties factoryProperties,
        Clock clock,
        Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
        SecretsProvider secretsProvider,
        TakeoverConfirmation takeoverConfirmation) {

    TakeResult runExplicit(
            TakeArguments takeArguments,
            String rawRef,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            TrackerAdapterFactory factory) {
        return runOneRef(
                takeArguments, rawRef, definition, trackerConfig, tracker, instanceId, factory, takeoverConfirmation);
    }

    /**
     * The per-ref disposition body shared by {@link #runExplicit} (this invocation's own {@link
     * #takeoverConfirmation}) and batch (always {@link TakeoverConfirmation#UNAVAILABLE} — FR4).
     *
     * <p>Implements FR9 of add-tracker-port; FR3, FR4 of add-factory-serve.
     */
    TakeResult runOneRef(
            TakeArguments takeArguments,
            String rawRef,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            TrackerAdapterFactory factory,
            TakeoverConfirmation confirmation) {
        // NFR-O1: the canonical ref is known as soon as short-ref expansion resolves it, before
        // fetchTask/dispose ever run — so every explicit-mode disposition outcome, including a
        // refusal (AwaitingHuman/Working/Finished/Gone in TakeDisposition, none of which reach any
        // deeper resume/fresh-claim MDC-setting code), is logged under the correct taskId.
        TaskRef ref = TakeRefResolution.resolve(rawRef, trackerConfig, trackerAdapterRegistry);
        MDC.put(wiring.taskIdMdcKey(), ref.id());
        // FR9, design D8: a full canonical id naming a repo the adapter cannot reconcile to the
        // configured binding (GitHub: neither the configured repo nor a rename predecessor of it)
        // is refused here — before fetchTask ever touches the foreign repo — as exit 15 (Skipped),
        // never silently acted on. Adapters whose refs carry no repo binding return empty.
        Optional<String> foreignRefusal = factory.refuseForeignRef(secretsProvider, trackerConfig, ref);
        if (foreignRefusal.isPresent()) {
            return new TakeResult.Skipped(UntrustedText.factory(foreignRefusal.get()));
        }
        // One of the three places a take order is assembled (design single-owner table of
        // introduce-take-order), and the explicit-mode place a TakeArguments becomes a run order.
        var run = new RunOrder(
                takeArguments.dir(),
                takeArguments.base(),
                definition,
                takeArguments.interactiveMode(),
                takeArguments.discardWork());
        var order = new TakeOrder(run, tracker.fetchTask(ref), tracker, instanceId);
        var disposition = new TakeDisposition(wiring, takeArguments.takeover(), confirmation, clock);
        long startedNanos = System.nanoTime();
        TakeResult result = disposition.dispose(order);
        summarize(result, startedNanos);
        return result;
    }

    TakeResult runBare(
            TakeArguments takeArguments,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId) {
        FactoryProperties.Tracker trackerProperties = factoryProperties.tracker();
        var bareAuto = new TakeBareAuto(
                wiring,
                trackerProperties.abortBackoffBase(),
                trackerProperties.abortBackoffCap(),
                clock,
                trackerConfig.wipLimit(),
                new Random());
        // The bare-mode place a TakeArguments becomes a run order (D1 of introduce-take-order). The
        // parser refuses --base on bare take, and a bare take has always salvaged — --discard-work
        // is not passed on here, exactly as before this order existed.
        var run = new RunOrder(takeArguments.dir(), null, definition, takeArguments.interactiveMode(), false);
        long startedNanos = System.nanoTime();
        TakeResult result = bareAuto.run(run, tracker, instanceId);
        summarize(result, startedNanos);
        return result;
    }

    /**
     * FR3, design D3 of harden-logging-observability: {@code take}'s end of the canonical task
     * summary, emitted from the same assembler and rendered by the same renderer a {@code serve}
     * slot uses — the point of "one summary form for all modes" is that a {@code take} log and a
     * {@code serve} log read alike.
     *
     * <p>Emitted here, at the outermost point that still holds the task's {@code taskId} MDC, so
     * the summary really is the last line of a grep by that id. Results describing a run that
     * never happened — an empty queue, a declined or foreign ref — assemble to no summary.
     *
     * <p>Wall time comes from {@link System#nanoTime()} rather than this record's {@link Clock}: a
     * duration must not be affected by a wall-clock adjustment landing mid-run.
     */
    private static void summarize(TakeResult result, long startedNanos) {
        TaskSummary summary = TaskSummaryAssembler.assemble(result, WallTime.since(startedNanos));
        if (summary != null) {
            AnchorLog.taskSummary(summary);
        }
    }

    /**
     * Batch mode ({@code take <ref> <ref> ...}, two or more refs), validated by {@link
     * TakeArgumentsParser}: delegates to {@link TakeBatch#dispatch} for the scheduler-driven
     * per-ref disposition matrix (see its Javadoc for the full contract).
     *
     * <p>Implements FR3, FR4, D6 of add-factory-serve.
     *
     * @param slots the concurrency limit N (design D3's {@code factory.serve.slots}); positive
     * @throws InterruptedException if interrupted while waiting for a free slot or an in-flight ref
     */
    List<TakeBatchOutcome> runBatch(
            TakeArguments takeArguments,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            TrackerAdapterFactory factory,
            int slots)
            throws InterruptedException {
        return TakeBatch.dispatch(this, takeArguments, definition, trackerConfig, tracker, instanceId, factory, slots);
    }
}
