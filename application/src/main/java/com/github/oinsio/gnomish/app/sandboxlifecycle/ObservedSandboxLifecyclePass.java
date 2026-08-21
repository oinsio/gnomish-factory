package com.github.oinsio.gnomish.app.sandboxlifecycle;

import com.github.oinsio.gnomish.app.lease.LivenessVerdict;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import java.nio.file.Path;
import java.util.List;

/**
 * The daemon's observed sweep pass (NFR-O1, NFR-O2 of add-serve-sandbox-lifecycle): wraps the
 * real {@link SandboxLifecyclePass} so each run is bracketed as one tick — the tally is reset
 * before, every verdict reaches both the snapshot's {@link SweepTickLog} and the ledger's action
 * sink during, and the completed {@link SweepTickRecord} is handed to the tick sink after.
 *
 * <p>The brackets are here rather than in {@code SandboxLifecycleTick} because only the daemon
 * observes ticks at all: `run` and `take` evaluate the same policy once, with no vitals and no
 * ledger (proposal NG4), and the scheduler stays a scheduler.
 *
 * <p>A failed pass completes no tick: the exception propagates untouched, so the tick sink writes
 * no summary line and {@link SweepTickLog#lastTick()} keeps the last pass that actually finished.
 * A partial tally published as a completed tick would read as a healthy sweep that found less
 * work, which is exactly the silent stall the tick-overdue alert exists to catch (NFR-O3).
 *
 * <p>Implements NFR-O1, NFR-O2 of add-serve-sandbox-lifecycle.
 */
public final class ObservedSandboxLifecyclePass implements SandboxLifecyclePass {

    private final SandboxLifecyclePass delegate;
    private final SweepTickLog tickLog;
    private final SweepTickListener tickSink;
    private final SweepVerdictFanout sink;

    /**
     * @param delegate the real policy evaluation this wraps; never null
     * @param tickLog the snapshot's per-tick record, both a verdict sink and the tick's tally
     * @param verdictSink the additional per-verdict sink (the ledger's action lines); never null
     * @param tickSink notified once per completed tick (the ledger's summary line); never null
     */
    public ObservedSandboxLifecyclePass(
            SandboxLifecyclePass delegate,
            SweepTickLog tickLog,
            SweepVerdictListener verdictSink,
            SweepTickListener tickSink) {
        this.delegate = delegate;
        this.tickLog = tickLog;
        this.tickSink = tickSink;
        this.sink = new SweepVerdictFanout(List.of(tickLog, verdictSink));
    }

    @Override
    public String run(Path cloneDir, LivenessVerdict liveness) {
        tickLog.beginTick();
        String summary = delegate.run(cloneDir, liveness, sink);
        tickSink.onTickCompleted(tickLog.endTick());
        return summary;
    }
}
